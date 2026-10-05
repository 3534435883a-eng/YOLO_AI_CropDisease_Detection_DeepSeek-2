package com.example.Ece.agent.library;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Read-only catalogue for the local PDFs bundled with this workspace. */
@Service
public class LocalKnowledgeLibraryService {

    private final Path root;

    public LocalKnowledgeLibraryService(
            @Value("${agent.knowledge.library-root:../\u519C\u4E1A\u8BBA\u6587}") String configuredRoot) {
        this.root = Paths.get(configuredRoot).toAbsolutePath().normalize();
    }

    public Map<String, Object> listDocuments() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            result.put("available", Boolean.FALSE);
            result.put("count", Integer.valueOf(0));
            result.put("documents", Collections.emptyList());
            return result;
        }

        List<Map<String, Object>> documents = new ArrayList<Map<String, Object>>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isPdf)
                    .forEach(path -> addDocument(path, documents));
        } catch (IOException | UncheckedIOException | SecurityException error) {
            result.put("available", Boolean.FALSE);
            result.put("count", Integer.valueOf(0));
            result.put("documents", Collections.emptyList());
            return result;
        }

        documents.sort(Comparator
                .comparingInt((Map<String, Object> item) -> categoryOrder(String.valueOf(item.get("category"))))
                .thenComparing(item -> String.valueOf(item.get("fileName")), String.CASE_INSENSITIVE_ORDER));
        result.put("available", Boolean.TRUE);
        result.put("count", Integer.valueOf(documents.size()));
        result.put("documents", documents);
        return result;
    }

    /** Resolves only IDs generated from files currently present below the configured root. */
    public Optional<Path> resolvePdf(String documentId) {
        if (documentId == null || !documentId.matches("[a-f0-9]{64}")
                || !Files.isDirectory(root) || !Files.isReadable(root)) {
            return Optional.empty();
        }
        try {
            Path realRoot = root.toRealPath();
            try (Stream<Path> files = Files.walk(realRoot)) {
                Optional<Path> match = files.filter(Files::isRegularFile)
                        .filter(this::isPdf)
                        .filter(path -> documentId.equals(documentId(path, realRoot)))
                        .map(this::realPathInsideRoot)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst();
                return match;
            }
        } catch (IOException | UncheckedIOException | SecurityException error) {
            return Optional.empty();
        }
    }

    /** Verifies the original file associated with a reviewed summary before serving it. */
    public Optional<Path> resolveVerifiedPdf(String id, String expectedSha256, long expectedBytes) {
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}") || expectedBytes <= 0) {
            return Optional.empty();
        }
        Optional<Path> resolved = resolvePdf(id);
        if (!resolved.isPresent()) return Optional.empty();
        try {
            Path file = resolved.get();
            if (Files.size(file) != expectedBytes) return Optional.empty();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte part : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", part & 0xff));
            return expectedSha256.equals(hex.toString()) ? resolved : Optional.empty();
        } catch (IOException | SecurityException error) {
            return Optional.empty();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private void addDocument(Path path, List<Map<String, Object>> documents) {
        try {
            Path realRoot = root.toRealPath();
            Optional<Path> safePath = realPathInsideRoot(path, realRoot);
            if (!safePath.isPresent()) return;

            Path file = safePath.get();
            String relative = relativeIdPath(file, realRoot);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", sha256(relative));
            item.put("fileName", file.getFileName().toString());
            item.put("category", category(relative, file.getFileName().toString()));
            Path parent = realRoot.relativize(file).getParent();
            item.put("folder", parent == null ? "农业论文" : parent.toString().replace('\\', '/'));
            item.put("sizeBytes", Long.valueOf(Files.size(file)));
            documents.add(item);
        } catch (IOException | SecurityException ignored) {
            // A file that disappears or becomes unreadable during listing is omitted.
        }
    }

    private Optional<Path> realPathInsideRoot(Path path) {
        try {
            return realPathInsideRoot(path, root.toRealPath());
        } catch (IOException | SecurityException error) {
            return Optional.empty();
        }
    }

    private Optional<Path> realPathInsideRoot(Path path, Path realRoot) {
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath) || !isPdf(realPath)) {
                return Optional.empty();
            }
            return Optional.of(realPath);
        } catch (IOException | SecurityException error) {
            return Optional.empty();
        }
    }

    private boolean isPdf(Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String documentId(Path path, Path realRoot) {
        return sha256(relativeIdPath(path, realRoot));
    }

    private String relativeIdPath(Path path, Path realRoot) {
        return realRoot.relativize(path).toString().replace('\\', '/');
    }

    private String category(String relativePath, String fileName) {
        String firstSegment = relativePath.contains("/")
                ? relativePath.substring(0, relativePath.indexOf('/')) : "";
        if ("标准_其他作物".equals(firstSegment)) return "其他作物标准";
        if ("标准".equals(firstSegment) || isTomatoStandardName(fileName)) return "设施番茄标准";
        return "研究论文";
    }

    private boolean isTomatoStandardName(String fileName) {
        String upper = fileName.toUpperCase(Locale.ROOT);
        return upper.startsWith("DB") || upper.startsWith("NY/T") || upper.startsWith("NY-T");
    }

    private int categoryOrder(String category) {
        if ("研究论文".equals(category)) return 0;
        if ("设施番茄标准".equals(category)) return 1;
        if ("其他作物标准".equals(category)) return 2;
        return 3;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) hex.append(String.format(Locale.ROOT, "%02x", part & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
