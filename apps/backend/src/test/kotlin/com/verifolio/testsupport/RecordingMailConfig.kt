package com.verifolio.testsupport

import com.verifolio.notifications.MailPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

class RecordingMailPort : MailPort {
    data class Sent(val to: String, val subject: String, val textBody: String)
    val sent = CopyOnWriteArrayList<Sent>()
    override fun send(to: String, subject: String, textBody: String) {
        sent += Sent(to, subject, textBody)
    }
}

@TestConfiguration
class RecordingMailConfig {
    @Bean @Primary fun recordingMailPort(): RecordingMailPort = RecordingMailPort()
}
