package com.stellartrack.replay.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.replay.application.ReplayService;
import com.stellartrack.replay.domain.ReplayJobStatus;
import com.stellartrack.replay.persistence.ReplayJobEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(ApiPaths.REPLAY)
@Tag(name = "Historical Replay")
public class ReplayController {

    public record ReplayJobView(UUID id, String originalFilename, long sizeBytes, String sha256, ReplayJobStatus status,
            String statusReason, UUID configurationId, Integer configurationVersion, String engineVersion,
            Integer totalRows, int processedRows, String errors, String result, String createdBy, Instant createdAt,
            Instant startedAt, Instant finishedAt) {

        static ReplayJobView from(ReplayJobEntity j) {
            return new ReplayJobView(j.getId(), j.getOriginalFilename(), j.getSizeBytes(), j.getSha256(), j.getStatus(),
                    j.getStatusReason(), j.getConfigurationId(), j.getConfigurationVersion(), j.getEngineVersion(),
                    j.getTotalRows(), j.getProcessedRows(), j.getErrors(), j.getResult(), j.getCreatedBy(),
                    j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Upload a historical file; validation runs in the background")
    public ReplayJobView upload(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID configurationId,
            @RequestParam(required = false) Integer configurationVersion) throws IOException {
        ReplayJobEntity job = replay.upload(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), configurationId, configurationVersion,
                AdminPrincipal.current().actorId());
        replay.process(job.getId());
        return ReplayJobView.from(job);
    }

    @GetMapping
    public PageResponse<ReplayJobView> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return PageResponse.from(replay.list(PageRequests.of(page, size, Sort.unsorted())), ReplayJobView::from);
    }

    @GetMapping("/{id}")
    public ReplayJobView get(@PathVariable UUID id) {
        return ReplayJobView.from(replay.get(id));
    }
}
