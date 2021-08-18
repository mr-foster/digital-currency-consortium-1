package io.provenance.digitalcurrency.consortium.bankclient

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Feign
import feign.Headers
import feign.Param
import feign.RequestLine
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import io.provenance.digitalcurrency.consortium.api.DepositFiatRequest
import org.springframework.http.ResponseEntity
import java.util.UUID

@Headers("Content-Type: application/json")
interface BankClient {

    // TODO need to make this a registration - hard-coded for NYCB right now
    @RequestLine("POST /bankmember/api/v1/mints/complete/{uuid}")
    fun updateMintStatus(@Param("uuid") uuid: UUID, status: String): ResponseEntity<String>

    // TODO need to make this a registration - hard-coded for NYCB right now
    @RequestLine("POST /bankmember/api/v1/fiat/deposits")
    fun depositFiat(request: DepositFiatRequest): ResponseEntity<String>

    class Builder(
        private val url: String,
        private val objectMapper: ObjectMapper
    ) {
        fun build(): BankClient = Feign.builder()
            .encoder(JacksonEncoder(objectMapper))
            .decoder(JacksonDecoder(objectMapper))
            .target(BankClient::class.java, url)
    }
}
