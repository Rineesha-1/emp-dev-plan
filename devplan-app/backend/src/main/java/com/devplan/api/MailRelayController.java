package com.devplan.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.mail.internet.MimeMessage;

/**
 * Sends the mails of the case model's mail process tasks (Send welcome e-mail, Notify IT, Send onboarding complete
 * e-mail). Engine 1.1.35 has no mail process implementation, so those tasks are HTTP process tasks that post the
 * mail - recipients, subject and body, all written in the .process file - here, and this relays it to the SMTP server
 * (the CaseFabric mailcatcher). Only reachable inside the docker network (not under /api, which nginx forwards) and
 * only with the shared X-Mail-Token.
 */
@RestController
public class MailRelayController {
    private static final Logger log = LoggerFactory.getLogger(MailRelayController.class);

    public record Mail(String to, String cc, String subject, String html) {
    }

    private final JavaMailSender mailSender;
    private final String from;
    private final String token;

    public MailRelayController(JavaMailSender mailSender, @Value("${devplan.mail.from}") String from,
                               @Value("${devplan.mail.relay-token}") String token) {
        this.mailSender = mailSender;
        this.from = from;
        this.token = token;
    }

    @PostMapping("/internal/mail")
    public void send(@RequestHeader(value = "X-Mail-Token", required = false) String given, @RequestBody Mail mail)
            throws Exception {
        if (!token.equals(given)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong mail token");
        }
        if (mail.to() == null || mail.to().isBlank() || "null".equals(mail.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The mail has no recipient");
        }
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(from);
        helper.setTo(mail.to());
        if (mail.cc() != null && !mail.cc().isBlank() && !"null".equals(mail.cc())) {
            helper.setCc(mail.cc());
        }
        helper.setSubject(mail.subject() == null ? "" : mail.subject());
        helper.setText(mail.html() == null ? "" : mail.html(), true);
        mailSender.send(message);
        log.info("Mail sent to {}: {}", mail.to(), mail.subject());
    }
}
