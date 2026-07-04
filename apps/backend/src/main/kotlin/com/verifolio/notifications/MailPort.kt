package com.verifolio.notifications

interface MailPort {
    fun send(to: String, subject: String, textBody: String)
}
