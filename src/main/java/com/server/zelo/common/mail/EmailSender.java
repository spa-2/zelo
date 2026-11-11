package com.server.zelo.common.mail;

public interface EmailSender {
    void send(String to, String title, String body);
}
