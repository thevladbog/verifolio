package com.verifolio.platform.web

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class ApiDocsUiController {

    @GetMapping("/docs", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun scalarUi(): String = """
        <!doctype html>
        <html>
          <head>
            <title>Verifolio API Reference</title>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
          </head>
          <body>
            <script id="api-reference" data-url="/v3/api-docs"></script>
            <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
          </body>
        </html>
    """.trimIndent()
}
