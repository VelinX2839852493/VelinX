package com.velinx.dto.frontendadapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionStartRequest(String name,
                                  String role,
                                  boolean hasWorld,
                                  String profilePath,
                                  String worldPath,
                                  String workPath
                                  ) {
}

