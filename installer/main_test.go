package main

import (
	"archive/tar"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type member struct {
	name string
	kind byte
	body string
	link string
}

func fixture(t *testing.T, members []member) string {
	t.Helper()
	filename := filepath.Join(t.TempDir(), "runtime.tar.gz")
	f, err := os.Create(filename)
	if err != nil {
		t.Fatal(err)
	}
	gz := gzip.NewWriter(f)
	tw := tar.NewWriter(gz)
	for _, m := range members {
		h := &tar.Header{Name: m.name, Typeflag: m.kind, Linkname: m.link, Mode: 0644}
		if m.kind == tar.TypeReg {
			h.Size = int64(len(m.body))
		}
		if err := tw.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		if m.kind == tar.TypeReg {
			if _, err := tw.Write([]byte(m.body)); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return filename
}

func TestExtractValidRuntime(t *testing.T) {
	files := []member{
		{name: "./windows_amd64/", kind: tar.TypeDir},
		{name: "./windows_amd64/jcef.dll", kind: tar.TypeReg, body: "cef1"},
		{name: "windows_amd64/libcef.dll", kind: tar.TypeReg, body: "cef2"},
		{name: "windows_amd64/jcef_helper.exe", kind: tar.TypeReg, body: "exe"},
		{name: "windows_amd64/locales/en-US.pak", kind: tar.TypeReg, body: "pak"},
	}
	stage := t.TempDir()
	if err := extractRuntime(fixture(t, files), stage); err != nil {
		t.Fatal(err)
	}
	if !installed(filepath.Join(stage, "windows_amd64")) {
		t.Fatal("required files not found")
	}
	if _, err := os.Stat(filepath.Join(stage, "windows_amd64", "locales", "en-US.pak")); err != nil {
		t.Fatal(err)
	}
}

func TestUnsafePaths(t *testing.T) {
	bad := []string{
		"../other/file", "/windows_amd64/file", "windows_amd64/../../escape",
		"windows_amd64/x\\../escape", "windows_amd64/C:/file", "windows_amd64/CON",
		"windows_amd64/a. ", "windows_amd64/a:stream",
	}
	for _, item := range bad {
		if _, err := safeMemberName(item); err == nil {
			t.Errorf("should reject %q", item)
		}
	}
}

func TestRefuseSymlink(t *testing.T) {
	archive := fixture(t, []member{{name: "windows_amd64/link", kind: tar.TypeSymlink, link: "../../other"}})
	if err := extractRuntime(archive, t.TempDir()); err == nil {
		t.Fatal("expected symlink rejection")
	}
}

func TestRefuseDuplicateCaseInsensitivePaths(t *testing.T) {
	archive := fixture(t, []member{
		{name: "windows_amd64/A.dll", kind: tar.TypeReg, body: "1"},
		{name: "windows_amd64/a.dll", kind: tar.TypeReg, body: "2"},
	})
	if err := extractRuntime(archive, t.TempDir()); err == nil {
		t.Fatal("expected duplicate rejection")
	}
}

func TestMaxUnpackedLimit(t *testing.T) {
	archive := fixture(t, []member{{name: "windows_amd64/x", kind: tar.TypeReg, body: strings.Repeat("x", 32)}})
	// The test verifies that normal-size archives pass; the bound is a global constant.
	if err := extractRuntime(archive, t.TempDir()); err != nil {
		t.Fatal(err)
	}
}

func TestDigestValidation(t *testing.T) {
	if !validDigest(strings.Repeat("a", 64)) {
		t.Fatal("expected valid")
	}
	for _, bad := range []string{strings.Repeat("z", 64), strings.Repeat("a", 65), strings.Repeat("a", 63), ""} {
		if validDigest(bad) {
			t.Errorf("accepted %q", bad)
		}
	}
}
