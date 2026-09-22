package com.example.Ece.agent.tool;

/** 工具执行失败（入参非法、依赖不可用等），由编排层记录并决定继续或终止。 */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
