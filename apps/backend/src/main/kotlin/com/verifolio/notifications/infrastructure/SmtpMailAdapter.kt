package com.verifolio.notifications.infrastructure

import com.verifolio.notifications.MailPort
import com.verifolio.platform.VerifolioProperties
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
internal class SmtpMailAdapter(
    private val mailSender: JavaMailSender,
    private val props: VerifolioProperties,
) : MailPort {
    override fun send(to: String, subject: String, textBody: String) {
        val message = SimpleMailMessage()
        message.setTo(to)
        message.from = props.mail.from
        message.subject = subject
        message.text = textBody
        mailSender.send(message)
    }
}
