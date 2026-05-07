package com.moneylens.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    public EmailService(org.springframework.mail.javamail.JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailService.class);

    private final org.springframework.mail.javamail.JavaMailSender mailSender;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async
    public void sendVerificationEmail(String toEmail, String fullName, String token) {
        String link = frontendUrl + "/verify-email?token=" + token;
        String subject = "Verify your MoneyLens account";
        String body = buildVerificationEmailBody(fullName, link);
        sendEmail(toEmail, subject, body);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String link = frontendUrl + "/reset-password?token=" + token;
        String subject = "Reset your MoneyLens password";
        String body = buildPasswordResetEmailBody(fullName, link);
        sendEmail(toEmail, subject, body);
    }

    @Async
    public void sendWelcomeEmail(String toEmail, String fullName) {
        String subject = "Welcome to MoneyLens 🔍";
        String body = buildWelcomeEmailBody(fullName);
        sendEmail(toEmail, subject, body);
    }

    private void sendEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "MoneyLens");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            // Don't throw - email failure shouldn't break the auth flow
        }
    }

    private String buildVerificationEmailBody(String name, String link) {
        return """
            <div style="font-family:sans-serif;max-width:600px;margin:0 auto;background:#0a0a0c;color:#e4e4e7;padding:40px;border-radius:16px;">
              <div style="text-align:center;margin-bottom:32px;">
                <div style="width:48px;height:48px;background:linear-gradient(135deg,#E8622A,#D4A017);border-radius:12px;display:inline-flex;align-items:center;justify-content:center;font-size:24px;color:#000;font-weight:bold;">M</div>
                <h1 style="color:#fff;margin:16px 0 8px;font-size:24px;">Verify your email</h1>
                <p style="color:#71717a;margin:0;">Hi %s, almost there!</p>
              </div>
              <p style="color:#a1a1aa;line-height:1.6;">Click the button below to verify your MoneyLens account and start understanding your finances like never before.</p>
              <div style="text-align:center;margin:32px 0;">
                <a href="%s" style="display:inline-block;background:#E8622A;color:#fff;padding:14px 32px;border-radius:100px;text-decoration:none;font-weight:700;font-size:16px;">Verify my email →</a>
              </div>
              <p style="color:#52525b;font-size:12px;text-align:center;">This link expires in 24 hours. If you didn't create this account, you can safely ignore this email.</p>
            </div>
            """.formatted(name, link);
    }

    private String buildPasswordResetEmailBody(String name, String link) {
        return """
            <div style="font-family:sans-serif;max-width:600px;margin:0 auto;background:#0a0a0c;color:#e4e4e7;padding:40px;border-radius:16px;">
              <div style="text-align:center;margin-bottom:32px;">
                <div style="width:48px;height:48px;background:linear-gradient(135deg,#E8622A,#D4A017);border-radius:12px;display:inline-flex;align-items:center;justify-content:center;font-size:24px;color:#000;font-weight:bold;">M</div>
                <h1 style="color:#fff;margin:16px 0 8px;font-size:24px;">Reset your password</h1>
                <p style="color:#71717a;margin:0;">Hi %s, we got your request.</p>
              </div>
              <p style="color:#a1a1aa;line-height:1.6;">Click the button below to reset your password. This link is valid for 1 hour.</p>
              <div style="text-align:center;margin:32px 0;">
                <a href="%s" style="display:inline-block;background:#E8622A;color:#fff;padding:14px 32px;border-radius:100px;text-decoration:none;font-weight:700;font-size:16px;">Reset password →</a>
              </div>
              <p style="color:#52525b;font-size:12px;text-align:center;">If you didn't request a password reset, please ignore this email. Your password won't change.</p>
            </div>
            """.formatted(name, link);
    }

    private String buildWelcomeEmailBody(String name) {
        return """
            <div style="font-family:sans-serif;max-width:600px;margin:0 auto;background:#0a0a0c;color:#e4e4e7;padding:40px;border-radius:16px;">
              <div style="text-align:center;margin-bottom:32px;">
                <div style="width:48px;height:48px;background:linear-gradient(135deg,#E8622A,#D4A017);border-radius:12px;display:inline-flex;align-items:center;justify-content:center;font-size:24px;color:#000;font-weight:bold;">M</div>
                <h1 style="color:#fff;margin:16px 0 8px;font-size:24px;">Welcome to MoneyLens 🔍</h1>
                <p style="color:#71717a;margin:0;">Hi %s, you're in!</p>
              </div>
              <p style="color:#a1a1aa;line-height:1.6;">You can now upload your first bank statement and discover exactly where your money is going — and what to do about it.</p>
              <div style="text-align:center;margin:32px 0;">
                <a href="%s/dashboard" style="display:inline-block;background:#E8622A;color:#fff;padding:14px 32px;border-radius:100px;text-decoration:none;font-weight:700;font-size:16px;">Go to dashboard →</a>
              </div>
            </div>
            """.formatted(name, frontendUrl);
    }
}
