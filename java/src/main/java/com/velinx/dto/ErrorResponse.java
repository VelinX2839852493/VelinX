package com.velinx.dto;

public record ErrorResponse(boolean ok, ErrorPayload error) {
}
