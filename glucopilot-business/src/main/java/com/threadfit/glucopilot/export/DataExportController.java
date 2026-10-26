package com.threadfit.glucopilot.export;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class DataExportController {
    private final DataExportService service;
    @PostMapping("/api/v1/data-export-tasks") public ApiResponse<DataExportService.Task> create(@RequestBody DataExportService.Create p){return ApiResponse.ok(service.create(RequestContext.requireUserId(),p));}
    @GetMapping("/api/v1/data-export-tasks") public ApiResponse<PageResult<DataExportService.Task>> mine(@Valid PageQuery q){return ApiResponse.ok(service.list(RequestContext.requireUserId(),null,null,q));}
    @PostMapping("/api/v1/data-export-tasks/{id}/generate") public ApiResponse<DataExportService.Task> generateMine(@PathVariable long id){return ApiResponse.ok(service.generate(RequestContext.requireUserId(),id));}
    @GetMapping("/api/v1/data-export-tasks/{id}/file") public ResponseEntity<byte[]> fileMine(@PathVariable long id){return file(service.file(RequestContext.requireUserId(),id),id);}
    @DeleteMapping("/api/v1/data-export-tasks/{id}") public ApiResponse<Void> removeMine(@PathVariable long id){service.remove(RequestContext.requireUserId(),id);return ApiResponse.ok();}
    @GetMapping("/admin/v1/exports") @PreAuthorize("@ss.hasPermi('export:task:manage')") public ApiResponse<PageResult<DataExportService.Task>> list(@RequestParam(required=false) String status,@RequestParam(required=false) String keyword,@Valid PageQuery q){return ApiResponse.ok(service.list(null,status,keyword,q));}
    @PostMapping("/admin/v1/exports/{id}/generate") @PreAuthorize("@ss.hasPermi('export:task:manage')") public ApiResponse<DataExportService.Task> generate(@PathVariable long id){return ApiResponse.ok(service.generate(null,id));}
    @GetMapping("/admin/v1/exports/{id}/file") @PreAuthorize("@ss.hasPermi('export:task:manage')") public ResponseEntity<byte[]> fileAdmin(@PathVariable long id){return file(service.file(null,id),id);}
    private ResponseEntity<byte[]> file(byte[] bytes,long id){return ResponseEntity.ok().contentType(MediaType.parseMediaType(DataExportService.MIME)).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=glucopilot-export-"+id+".pdf").body(bytes);}
}
