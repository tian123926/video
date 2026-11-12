package com.vod.main.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索词库 t_search_keyword：热词、同义词扩展。
 */
@Data
@TableName("t_search_keyword")
public class SearchKeyword {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String word;

    /** 同义词，英文逗号分隔 */
    private String synonyms;

    private Integer weight;

    /** 1=热词 */
    private Integer isHot;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
