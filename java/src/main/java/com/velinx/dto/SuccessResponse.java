package com.velinx.dto;

public record SuccessResponse<T>(boolean ok, T data) {
}
