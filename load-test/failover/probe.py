#!/usr/bin/env python3
"""장애 대응 테스트용 프로브: 핵심 기능 몇 개를 1초 간격으로 계속 호출하고 결과를 CSV로 남긴다.

Redis나 DB에 장애를 일으키는 동안 "무엇이 언제부터 언제까지 실패했나"를 기록하는 도구다. 외부 패키지 없이 파이썬 표준 라이브러리만 쓴다.
기본 시드(카테고리, 상품)와 관리자 계정 하나만 있으면 돌아간다. 계정은 ZELO_SEED_ADMIN_EMAIL, ZELO_SEED_ADMIN_PASSWORD로 만든 것을 쓴다.

프로브(무엇을 건드리는가)
  read        GET  /api/categories            Redis 캐시. Redis가 죽으면 DB로 우회하므로 계속 성공해야 한다(우아한 성능 저하)
  db_read     GET  /api/users/me/addresses    DB 읽기(JWT는 서버 상태가 없다). Redis와 무관해야 한다
  login       POST /api/users/login           Redis(리프레시 토큰, 로그인 시도 횟수) + DB
  reissue     POST /api/auth/reissue          Redis(리프레시 토큰 교체)
  addr_create POST /api/users/me/addresses    Redis 분산락 + DB 쓰기
  addr_delete DELETE /api/users/me/addresses/{id}  Redis 분산락 + DB 쓰기 (시작 전에 미리 만들어 둔 배송지를 1초에 하나씩 지운다)

배송지는 시작 전에 --prefill 개를 미리 만든다. 삭제 프로브가 create 성공과 상관없이 장애 중에도 계속 호출되게 하려는 것이다.
프로브가 만드는 배송지 이름은 fo<실행값>-... 이고, 중간에 끊겨 남은 것은 --cleanup으로 지운다(기본 배송지는 건드리지 않는다).

끝나면 "성공 응답을 받은 쓰기가 실제로 남아 있는가"를 확인한다(verify.json).
사용: probe.py --base-url https://zelo.wyshin.click --email admin@... --password ... --out results/run1
      probe.py --base-url ... --email ... --password ... --cleanup   (남은 프로브 배송지만 지우고 끝낸다)
"""
import argparse
import concurrent.futures
import csv
import json
import os
import re
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

PROBES = ["read", "db_read", "login", "reissue", "addr_create", "addr_delete"]


def now_iso():
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="milliseconds")


class State:
    """프로브들이 함께 쓰는 로그인 상태와 쓰기 기록."""

    def __init__(self):
        self.lock = threading.Lock()
        self.token = None
        self.cookie = None
        self.seq = 0
        self.created = set()  # 성공(201)을 받은 배송지 id
        self.deleted = set()  # 삭제 성공(204)을 받은 배송지 id
        self.pool = []  # 미리 만들어 둔, 삭제 프로브가 지울 배송지 id (지우다 실패하면 다시 넣는다)
        self.session_lost = 0  # 재발급이 401로 끝난 횟수. 원인은 모른다(토큰 소실로 단정하지 않는다)


def http(method, url, body=None, headers=None, timeout=10.0):
    """(status, headers, text, ms, err). 연결 실패나 시간 초과는 status 0."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", "replace")
            return response.status, response.headers, text, (time.monotonic() - started) * 1000, ""
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", "replace")
        return error.code, error.headers, text, (time.monotonic() - started) * 1000, ""
    except Exception as error:  # 연결 거부, 시간 초과, DNS 오류 등
        return 0, {}, "", (time.monotonic() - started) * 1000, type(error).__name__


def json_path(text, *keys):
    try:
        node = json.loads(text)
        for key in keys:
            node = node[key]
        return node
    except Exception:
        return None


def refresh_cookie(headers):
    for value in headers.get_all("Set-Cookie") or []:
        match = re.match(r"refreshToken=([^;]*)", value)
        if match and match.group(1):
            return match.group(1)
    return None


class Prober:
    def __init__(self, args):
        self.args = args
        self.base = args.base_url.rstrip("/")
        self.state = State()
        self.write_lock = threading.Lock()
        self.stop = threading.Event()
        self.running = {name: threading.Lock() for name in PROBES}
        self.last_ok = {}
        self.fail_since = {}
        self.started = time.monotonic()
        # 배송지 이름은 사용자 안에서 중복되면 409(DUPLICATE_ADDRESS_PLACE)라서, 실행마다 다른 접두어를 붙인다
        self.run_id = format(int(time.time()) % 16 ** 6, "x")
        os.makedirs(args.out, exist_ok=True)
        self.csv_file = open(os.path.join(args.out, "probe.csv"), "w", newline="", encoding="utf-8")
        self.writer = csv.writer(self.csv_file)
        self.writer.writerow(["ts", "t", "probe", "ok", "status", "ms", "err"])

    # ── 기록 ──
    def record(self, probe, status, ms, err=""):
        ok = 200 <= status < 300
        # t는 요청을 보낸 시각이다(응답이 끝난 시각이 아니다). 시간 초과까지 매달린 요청은 보낸 시각부터 실패한 것으로 본다
        t = max(0.0, time.monotonic() - self.started - ms / 1000)
        with self.write_lock:
            self.writer.writerow([now_iso(), f"{t:.3f}", probe, int(ok), status, f"{ms:.1f}", err])
            self.csv_file.flush()
            previous = self.last_ok.get(probe)
            self.last_ok[probe] = ok
            if not ok and previous is not False:
                self.fail_since[probe] = t
                print(f"  ✗ {probe} 실패 시작 status={status} {err}", flush=True)
            elif ok and previous is False:
                print(f"  ✓ {probe} 복구 ({t - self.fail_since.pop(probe, t):.1f}초 만에)", flush=True)
        return ok

    # ── 프로브 ──
    def auth_header(self):
        with self.state.lock:
            return {"Authorization": f"Bearer {self.state.token}"} if self.state.token else None

    def probe_read(self):
        status, _, _, ms, err = http("GET", f"{self.base}/api/categories", timeout=self.args.timeout)
        self.record("read", status, ms, err)

    def probe_db_read(self):
        header = self.auth_header()
        if not header:
            return
        status, _, _, ms, err = http("GET", f"{self.base}/api/users/me/addresses", headers=header, timeout=self.args.timeout)
        self.record("db_read", status, ms, err)

    def probe_login(self):
        status, headers, text, ms, err = http(
            "POST", f"{self.base}/api/users/login",
            body={"email": self.args.email, "password": self.args.password}, timeout=self.args.timeout)
        if self.record("login", status, ms, err):
            token = json_path(text, "data", "accessToken")
            cookie = refresh_cookie(headers)
            with self.state.lock:
                if token:
                    self.state.token = token
                if cookie:
                    self.state.cookie = cookie

    def probe_reissue(self):
        with self.state.lock:
            cookie = self.state.cookie
        if not cookie:
            return
        status, headers, text, ms, err = http(
            "POST", f"{self.base}/api/auth/reissue", headers={"Cookie": f"refreshToken={cookie}"}, timeout=self.args.timeout)
        ok = self.record("reissue", status, ms, err)
        with self.state.lock:
            if ok:
                token = json_path(text, "data", "accessToken")
                new_cookie = refresh_cookie(headers)
                if token:
                    self.state.token = token
                if new_cookie:
                    self.state.cookie = new_cookie
            elif status == 401:
                # 401 원인은 확인하지 못했다. 횟수만 센 뒤 다음 로그인으로 세션을 다시 만든다
                self.state.session_lost += 1
                self.state.cookie = None

    def address_body(self, name):
        return {"place": name[:20], "recipientName": "프로브", "recipientPhone": "010-0000-0000",
                "zipCode": "00000", "address": f"failover probe {name}", "isDefault": False}

    def probe_addr_create(self):
        header = self.auth_header()
        if not header:
            return
        with self.state.lock:
            self.state.seq += 1
            seq = self.state.seq
        status, _, text, ms, err = http(
            "POST", f"{self.base}/api/users/me/addresses", body=self.address_body(f"fo{self.run_id}-{seq}"),
            headers=header, timeout=self.args.timeout)
        if self.record("addr_create", status, ms, err):
            address_id = json_path(text, "data", "addressId")
            if address_id is not None:
                with self.state.lock:
                    self.state.created.add(address_id)

    def probe_addr_delete(self):
        header = self.auth_header()
        if not header:
            return
        with self.state.lock:
            if not self.state.pool:
                return  # 미리 만든 배송지를 다 썼다
            address_id = self.state.pool.pop(0)
        status, _, _, ms, err = http("DELETE", f"{self.base}/api/users/me/addresses/{address_id}", headers=header, timeout=self.args.timeout)
        with self.state.lock:
            if self.record("addr_delete", status, ms, err):
                self.state.deleted.add(address_id)
            else:
                self.state.pool.append(address_id)  # 실패한 것은 뒤에 넣어 나중에 다시 지운다(앞에 넣으면 같은 ID만 반복한다)

    def prefill(self, header):
        """삭제 프로브가 쓸 배송지를 장애 전에 미리 만든다."""
        print(f"배송지 {self.args.prefill}개를 미리 만든다...", flush=True)
        for number in range(self.args.prefill):
            status, _, text, _, err = http(
                "POST", f"{self.base}/api/users/me/addresses", body=self.address_body(f"fo{self.run_id}-p{number}"),
                headers=header, timeout=self.args.timeout)
            address_id = json_path(text, "data", "addressId")
            if status != 201 or address_id is None:
                print(f"배송지를 미리 만들지 못했다(status={status} {err}). {number}개만 만들고 계속한다.", file=sys.stderr)
                break
            self.state.pool.append(address_id)
            self.state.created.add(address_id)

    def cleanup(self):
        """이름이 fo<16진수>-로 시작하는 배송지(프로브가 만든 것)만 지운다."""
        header = self.auth_header()
        status, _, text, _, _ = http("GET", f"{self.base}/api/users/me/addresses", headers=header, timeout=self.args.timeout)
        data = json_path(text, "data")
        if status != 200 or not isinstance(data, list):
            print(f"배송지를 조회하지 못했다(status={status}).", file=sys.stderr)
            return 0
        removed = 0
        for item in data:
            if re.match(r"^fo[0-9a-f]+-", item.get("place") or "") and not item.get("isDefault"):
                code, _, _, _, _ = http("DELETE", f"{self.base}/api/users/me/addresses/{item['addressId']}", headers=header, timeout=self.args.timeout)
                removed += code == 204
        return removed

    # ── 실행 ──
    def run_probe(self, name, func):
        if not self.running[name].acquire(blocking=False):
            return  # 앞 호출이 아직 끝나지 않았다(시간 초과 대기 중). 그 호출이 끝나면서 실패로 기록된다
        try:
            func()
        except Exception as error:  # 프로브 자체의 오류는 기록하고 계속한다
            self.record(name, 0, 0, f"probe_error:{type(error).__name__}")
        finally:
            self.running[name].release()

    def setup(self):
        status, headers, text, _, err = http(
            "POST", f"{self.base}/api/users/login",
            body={"email": self.args.email, "password": self.args.password}, timeout=self.args.timeout)
        token = json_path(text, "data", "accessToken")
        if status != 200 or not token:
            print(f"로그인에 실패했다(status={status} {err}). 계정과 주소를 확인해라.", file=sys.stderr)
            sys.exit(2)
        self.state.token = token
        self.state.cookie = refresh_cookie(headers)
        if self.args.cleanup:
            return
        self.prefill({"Authorization": f"Bearer {token}"})
        self.started = time.monotonic()  # 미리 만드는 시간은 측정 시간에서 뺀다
        print(f"로그인 성공. 프로브를 시작한다 (간격 {self.args.interval}초, Ctrl+C로 종료)", flush=True)

    def verify(self):
        """성공 응답(201)을 받은 배송지가 실제로 남아 있고, 삭제 성공(204)을 받은 것은 없는지 확인한다."""
        header = self.auth_header()
        result = {"checked": False}
        if header:
            for _ in range(5):  # 장애 직후일 수 있어서 몇 번 다시 시도한다
                status, _, text, _, _ = http("GET", f"{self.base}/api/users/me/addresses", headers=header, timeout=self.args.timeout)
                data = json_path(text, "data")
                if status == 200 and isinstance(data, list):
                    server_ids = {item.get("addressId") for item in data}
                    expected = self.state.created - self.state.deleted
                    result = {
                        "checked": True,
                        "created_acked": len(self.state.created),
                        "deleted_acked": len(self.state.deleted),
                        "lost": sorted(expected - server_ids),  # 성공 응답을 받았는데 서버에 없다 = 유실
                        "resurrected": sorted(self.state.deleted & server_ids),  # 삭제 성공을 받았는데 남아 있다
                    }
                    break
                time.sleep(2)
        result["session_lost"] = self.state.session_lost
        # 지우지 못한 프로브 배송지 정리(확인이 끝난 뒤에 해야 한다)
        if header:
            result["cleaned"] = self.cleanup()
        with open(os.path.join(self.args.out, "verify.json"), "w", encoding="utf-8") as file:
            json.dump(result, file, ensure_ascii=False, indent=2)
        return result

    def loop(self):
        funcs = {"read": self.probe_read, "db_read": self.probe_db_read, "login": self.probe_login,
                 "reissue": self.probe_reissue, "addr_create": self.probe_addr_create,
                 "addr_delete": self.probe_addr_delete}
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=12)
        index = 0
        deadline = self.started + self.args.duration if self.args.duration else None
        while not self.stop.is_set():
            tick = self.started + index * self.args.interval
            wait = tick - time.monotonic()
            if wait > 0 and self.stop.wait(wait):
                break
            if deadline and time.monotonic() > deadline:
                break
            for key, func in funcs.items():
                # 로그인과 재발급은 시도 횟수 제한에 걸리지 않게 몇 주기에 한 번만 부른다
                if key in ("login", "reissue") and index % self.args.slow_every != 0:
                    continue
                pool.submit(self.run_probe, key, func)
            if index % 10 == 0 and index:
                print(f"  … {int(time.monotonic() - self.started)}초 경과", flush=True)
            index += 1
        # 진행 중인 호출이 끝나기를 기다린다(시간 초과까지)
        pool.shutdown(wait=True)

    def finish(self):
        self.csv_file.flush()
        result = self.verify()
        self.csv_file.close()
        with open(os.path.join(self.args.out, "meta.json"), "w", encoding="utf-8") as file:
            json.dump({"base_url": self.base, "interval": self.args.interval, "started": now_iso(),
                       "probes": PROBES}, file, ensure_ascii=False, indent=2)
        print(f"종료. 확인 결과: {json.dumps(result, ensure_ascii=False)}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--email", default=os.environ.get("PROBE_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("PROBE_PASSWORD"))
    parser.add_argument("--out", help="결과를 저장할 폴더(--cleanup이 아니면 필수)")
    parser.add_argument("--prefill", type=int, default=300, help="삭제 프로브가 쓸 배송지를 시작 전에 미리 만드는 개수. 측정 시간(초) 이상이어야 한다")
    parser.add_argument("--cleanup", action="store_true", help="프로브가 만든 배송지(fo...-)만 지우고 끝낸다")
    parser.add_argument("--duration", type=float, default=0, help="초. 0이면 Ctrl+C(SIGINT, SIGTERM)로 끝낼 때까지")
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--slow-every", type=int, default=3, help="로그인, 재발급은 이 주기마다 한 번")
    args = parser.parse_args()
    if not args.email or not args.password:
        parser.error("--email과 --password(또는 PROBE_EMAIL, PROBE_PASSWORD)가 필요하다")

    if not args.cleanup and not args.out:
        parser.error("--out이 필요하다")
    if args.cleanup:
        args.out = args.out or "/tmp/failover-cleanup"
    prober = Prober(args)
    signal.signal(signal.SIGINT, lambda *_: prober.stop.set())
    signal.signal(signal.SIGTERM, lambda *_: prober.stop.set())
    prober.setup()
    if args.cleanup:
        print(f"프로브 배송지 {prober.cleanup()}개를 지웠다")
        return
    prober.loop()
    prober.finish()


if __name__ == "__main__":
    main()
