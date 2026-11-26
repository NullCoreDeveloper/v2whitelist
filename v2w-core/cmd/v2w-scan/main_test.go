package main

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/kiktor/v2w-core/v2wscanner"
)

func TestRunFullScanModeEmptyLinks(t *testing.T) {
	var out bytes.Buffer
	opts := v2wscanner.DefaultScanOptions()

	runFullScanMode(context.Background(), nil, opts, &out)

	got := out.String()
	if got != "COMPLETE\t0\t0\n" {
		t.Fatalf("unexpected output for empty links: %q", got)
	}
}

func TestRunFullScanModeDeadNodeProtocol(t *testing.T) {
	deadLink := "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:1?security=none#TestDead"
	opts := v2wscanner.DefaultScanOptions()
	opts.TCPConcurrency = 2
	opts.VerifyConcurrency = 2
	opts.TimeoutSec = 1
	opts.FragTimeoutSec = 1

	var out bytes.Buffer
	runFullScanMode(context.Background(), []string{deadLink}, opts, &out)

	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 lines (RESULT + COMPLETE), got %d: %q", len(lines), out.String())
	}

	if !strings.HasPrefix(lines[0], "RESULT\t") {
		t.Fatalf("expected RESULT prefix on line 0, got %q", lines[0])
	}
	jsonPayload := strings.TrimPrefix(lines[0], "RESULT\t")
	var parsed v2wscanner.FullScanNodeResult
	if err := json.Unmarshal([]byte(jsonPayload), &parsed); err != nil {
		t.Fatalf("failed to unmarshal RESULT json: %v", err)
	}
	if parsed.Link != deadLink {
		t.Fatalf("unexpected link: got %q, want %q", parsed.Link, deadLink)
	}
	if parsed.Alive {
		t.Fatalf("expected dead node to have Alive=false")
	}
	if parsed.DelayMs != -1 {
		t.Fatalf("expected dead node DelayMs=-1, got %d", parsed.DelayMs)
	}
	if strings.TrimSpace(parsed.Error) == "" {
		t.Fatalf("expected non-empty Error for dead node")
	}

	if lines[1] != "COMPLETE\t0\t1" {
		t.Fatalf("expected COMPLETE\\t0\\t1, got %q", lines[1])
	}
}

func TestParseServicesFlagInline(t *testing.T) {
	raw := `[{"id":"youtube","name":"YouTube","url":"https://www.youtube.com/generate_204","enabled":true,"isCustom":false}]`
	svcs, err := parseServicesFlag(raw)
	if err != nil {
		t.Fatalf("parseServicesFlag failed: %v", err)
	}
	if len(svcs) != 1 || svcs[0].ID != "youtube" || !svcs[0].Enabled {
		t.Fatalf("unexpected services parsed: %+v", svcs)
	}
}
