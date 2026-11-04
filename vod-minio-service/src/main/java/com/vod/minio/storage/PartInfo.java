package com.vod.minio.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartInfo {

    private int partNumber;
    private String etag;
    private long size;
}
