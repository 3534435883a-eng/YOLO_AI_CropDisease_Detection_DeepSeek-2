package com.example.Ece.agent.controller;

import com.example.Ece.agent.library.LocalKnowledgeLibraryService;
import com.example.Ece.agent.rag.CitedKnowledgeReader;
import com.example.Ece.agent.rag.KnowledgeSource;
import com.example.Ece.agent.rag.KnowledgeSourceRepository;
import com.example.Ece.common.Result;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only catalogue of local reference PDFs and registered knowledge sources. */
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeLibraryController {

    @javax.annotation.Resource
    private LocalKnowledgeLibraryService libraryService;

    @javax.annotation.Resource
    private KnowledgeSourceRepository sourceRepository;

    @javax.annotation.Resource
    private CitedKnowledgeReader citedKnowledgeReader;

    @GetMapping("/library")
    public Result<?> library() {
        return Result.success(libraryService.listDocuments());
    }

    @GetMapping(value = "/library/{id}/content", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Resource> document(@PathVariable("id") String id) {
        try {
            Optional<Path> resolved = libraryService.resolvePdf(id);
            if (!resolved.isPresent()) return ResponseEntity.notFound().build();

            return pdfResponse(resolved.get());
        } catch (IOException | SecurityException error) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/sources")
    @SuppressWarnings("unchecked")
    public Result<?> sources() {
        Map<String, Map<String, Object>> references = citedKnowledgeReader.readLocalEvidence();
        Map<String, Map<String, Object>> documents = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> document : (List<Map<String, Object>>) libraryService.listDocuments().get("documents")) {
            documents.put(String.valueOf(document.get("id")), document);
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (KnowledgeSource source : sourceRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("sourceCode", source.getSourceCode());
            row.put("sourceName", source.getSourceName());
            row.put("sourceType", source.getSourceType());
            row.put("authorityLevel", Integer.valueOf(source.getAuthorityLevel()));
            row.put("version", source.getVersion());
            row.put("url", source.getUrl());
            row.put("licenseNote", source.getLicenseNote());
            Map<String, Object> reference = references.get(source.getSourceCode());
            if (reference != null) {
                Map<String, Object> document = new LinkedHashMap<String, Object>(
                        (Map<String, Object>) reference.get("localDocument"));
                Map<String, Object> present = documents.get(String.valueOf(document.get("id")));
                document.put("available", Boolean.valueOf(present != null
                        && document.get("sizeBytes").equals(present.get("sizeBytes"))));
                row.put("localDocument", document);
                row.put("reviewedSummaries", reference.get("reviewedSummaries"));
                row.put("summaryCount", reference.get("summaryCount"));
            }
            rows.add(row);
        }
        return Result.success(rows);
    }

    @GetMapping(value = "/sources/{sourceCode}/content", produces = MediaType.APPLICATION_PDF_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Resource> sourceDocument(@PathVariable("sourceCode") String sourceCode) {
        Map<String, Object> reference = citedKnowledgeReader.readLocalEvidence().get(sourceCode);
        if (reference == null) return ResponseEntity.notFound().build();
        Map<String, Object> document = (Map<String, Object>) reference.get("localDocument");
        String id = String.valueOf(document.get("id"));
        if (!libraryService.resolvePdf(id).isPresent()) return ResponseEntity.notFound().build();
        Optional<Path> verified = libraryService.resolveVerifiedPdf(id,
                String.valueOf(document.get("sha256")), ((Number) document.get("sizeBytes")).longValue());
        if (!verified.isPresent()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        try {
            return pdfResponse(verified.get());
        } catch (IOException | SecurityException error) {
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<Resource> pdfResponse(Path pdf) throws IOException {
        ContentDisposition disposition = ContentDisposition.builder("inline")
                .filename(pdf.getFileName().toString(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(Files.size(pdf))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(pdf));
    }
}
