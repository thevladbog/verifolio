package com.verifolio.notifications.infrastructure

import com.verifolio.notifications.MailPort
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
internal class SmtpMailAdapter(private val mailSender: JavaMailSender) : MailPort {
    override fun send(to: String, subject: String, textBody: String) {
        val message = SimpleMailMessage()
        message.setTo(to)
        message.from = "no-reply@verifolio.local"
        message.subject = subject
        message.text = textBody
        mailSender.send(message)
    }
}
