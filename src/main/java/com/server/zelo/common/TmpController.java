package com.server.zelo.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TmpController {

    // /api/admin 경로 추가시 삭제 할 컨트롤러, 배포가 아직 안 되어서 있어도 무방
    @GetMapping("/api/admin/test")
    public String test() {
        return "test";
    }
}
