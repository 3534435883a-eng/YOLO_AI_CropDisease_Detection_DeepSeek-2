package com.example.Ece.agent.rag;

/**
 * 知识来源登记。**没有出处就没有知识**：{@code sourceName} 与 {@code version} 为空时直接拒绝构造，
 * 从源头上保证每条入库知识都能回溯到可公开引用的来源。
 */
public class KnowledgeSource {

    private final String sourceCode;
    private final String sourceName;
    private final String sourceType;
    private final int authorityLevel;
    private final String url;
    private final String licenseNote;
    private final String version;

    public KnowledgeSource(String sourceCode, String sourceName, String sourceType, int authorityLevel,
                           String url, String licenseNote, String version) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceCode 不能为空");
        }
        if (sourceName == null || sourceName.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceName 不能为空：无出处的条目不得入库");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("version 不能为空：无版本的知识无法复核");
        }
        this.sourceCode = sourceCode.trim();
        this.sourceName = sourceName.trim();
        this.sourceType = sourceType == null ? "E" : sourceType.trim();
        this.authorityLevel = Math.max(1, Math.min(5, authorityLevel));
        this.url = url;
        this.licenseNote = licenseNote;
        this.version = version.trim();
    }

    public String getSourceCode() { return sourceCode; }

    public String getSourceName() { return sourceName; }

    public String getSourceType() { return sourceType; }

    public int getAuthorityLevel() { return authorityLevel; }

    public String getUrl() { return url; }

    public String getLicenseNote() { return licenseNote; }

    public String getVersion() { return version; }
}
