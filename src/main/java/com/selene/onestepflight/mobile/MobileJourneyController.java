package com.selene.onestepflight.mobile;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/mobile/installations")
public class MobileJourneyController {
    private final MobileJourneyService service;
    public MobileJourneyController(MobileJourneyService service) { this.service=service; }
    public record Registration(String id) {}
    @PostMapping public Map<String,Object> register(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Registration request) { return service.register(request.id(),auth); }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody MobileJourneyService.InstallationUpdate request) { return service.update(id,auth,request); }
    @PutMapping("/{id}/journey") public Map<String,Object> sync(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody MobileJourneyService.JourneySync request) { return service.sync(id,auth,request); }
    @PutMapping("/{id}/journey/activity") public Map<String,Object> bind(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody MobileJourneyService.ActivityBinding request) { return service.bind(id,auth,request); }
    @GetMapping("/{id}/journey") public Map<String,Object> get(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth) { return service.get(id,auth); }
    @DeleteMapping("/{id}/journey") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestParam long clientRevision,@RequestParam(required=false) String journeyKey) { service.clear(id,auth,clientRevision,journeyKey); }
    @ExceptionHandler({OptimisticLockingFailureException.class,DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT) public Map<String,String> concurrentWrite() { return Map.of("code","RETRY_CONCURRENT_WRITE"); }
}
