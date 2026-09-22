package com.example.Ece.agent.tool;

/** 工具权限：只读、仅生成草稿、或写操作需人工确认。 */
public enum ToolPermission {
    READ_ONLY,
    DRAFT,
    WRITE_REQUIRES_APPROVAL
}
