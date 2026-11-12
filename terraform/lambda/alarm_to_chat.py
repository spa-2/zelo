"""CloudWatch 알람(SNS)을 Slack 또는 Discord 웹훅으로 보낸다.

웹훅 주소는 SSM 파라미터(WEBHOOK_PARAMETER)에서 읽는다. 값이 CHANGE_ME이면 아무것도 보내지 않는다.
주소를 나중에 넣어도 5분 안에 반영된다(캐시 시간).
"""
import json
import os
import time
import urllib.request

_cache = {"url": None, "at": 0.0}
_TTL_SECONDS = 300


def _webhook_url():
    now = time.time()
    if _cache["url"] and now - _cache["at"] < _TTL_SECONDS:
        return _cache["url"]
    import boto3  # Lambda 런타임에 들어 있다. 로컬 테스트에서 import하지 않도록 함수 안에서 부른다

    value = boto3.client("ssm").get_parameter(
        Name=os.environ["WEBHOOK_PARAMETER"], WithDecryption=True
    )["Parameter"]["Value"]
    if value and value != "CHANGE_ME":
        _cache["url"], _cache["at"] = value, now
        return value
    return None


def format_message(raw, environment):
    """SNS 메시지를 사람이 읽는 한두 줄로 바꾼다. CloudWatch 알람이 아니면 원문 그대로 보낸다."""
    try:
        alarm = json.loads(raw)
    except (TypeError, ValueError):
        return f"[{environment}] {raw}"
    if not isinstance(alarm, dict) or "AlarmName" not in alarm:
        return f"[{environment}] {raw}"

    state = alarm.get("NewStateValue", "")
    icon = {"ALARM": ":rotating_light:", "OK": ":white_check_mark:"}.get(state, ":warning:")
    lines = [f"{icon} [{environment}] {alarm['AlarmName']} → {state}"]
    if alarm.get("AlarmDescription"):
        lines.append(alarm["AlarmDescription"])
    if alarm.get("NewStateReason"):
        lines.append(alarm["NewStateReason"])
    if alarm.get("StateChangeTime"):
        lines.append(f"시각: {alarm['StateChangeTime']}")
    return "\n".join(lines)


def build_payload(text, channel):
    # Slack은 text, Discord는 content 필드를 쓴다
    return {"content": text[:1900]} if channel == "discord" else {"text": text}


def handler(event, context):
    url = _webhook_url()
    if not url:
        print("웹훅 주소가 아직 SSM에 없어서 알림을 보내지 않는다")
        return {"sent": 0}

    environment = os.environ.get("ENVIRONMENT", "")
    channel = os.environ.get("CHANNEL", "slack")
    sent = 0
    for record in event.get("Records", []):
        text = format_message(record["Sns"].get("Message", ""), environment)
        request = urllib.request.Request(
            url,
            data=json.dumps(build_payload(text, channel)).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            print("보냄", response.status)
        sent += 1
    return {"sent": sent}
