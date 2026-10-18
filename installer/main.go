// Zomboid Map Companion Chromium Runtime Installer
// Console-only Windows x64 executable. No PowerShell or administrator rights required.
// The Chromium runtime is downloaded from CinemaMod only after the user consents.
package main

import (
	"archive/tar"
	"bufio"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

const (
	installerVersion = "1.0.0"
	revision         = "eaeb3d4370aa3526ee237ad1981ad59af3de4dd1"
	archiveURL       = "https://mcef-download.cinemamod.com/java-cef-builds/" + revision + "/windows_amd64.tar.gz"
	shaURL           = archiveURL + ".sha256"
	maxDownload      = int64(400) << 20
	maxUnpacked      = int64(3) << 30
	maxEntries       = 30000
)

var required = []string{"jcef.dll", "libcef.dll", "jcef_helper.exe"}
var digestPattern = regexp.MustCompile(`(?i)\b[a-f0-9]{64}\b`)
var winReserved = regexp.MustCompile(`(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)`)

func main() {
	os.Exit(execute(os.Args[1:], os.Stdin, os.Stdout))
}

func execute(args []string, in io.Reader, out io.Writer) int {
	reader := bufio.NewReader(in)
	exitCode := run(args, reader, out)
	// A double-clicked console executable should keep its window visible.
	fmt.Fprint(out, "\nPress Enter to close this window...")
	_, _ = reader.ReadString('\n')
	return exitCode
}

func run(args []string, input *bufio.Reader, out io.Writer) int {
	opts := flag.NewFlagSet("ZomboidMapCompanionChromiumInstaller", flag.ContinueOnError)
	opts.SetOutput(out)
	offlineArchive := opts.String("archive", "", "Optional local windows_amd64.tar.gz archive")
	providedSHA := opts.String("sha256", "", "SHA-256 for offline/manual verification (64 hex characters)")
	autoConfirm := opts.Bool("yes", false, "Skip confirmation (advanced users)")
	if err := opts.Parse(args); err != nil {
		return 2
	}

	fmt.Fprintf(out, "ZOMBOID MAP COMPANION - CHROMIUM INSTALLER v%s\n", installerVersion)
	fmt.Fprintln(out, "=================================================")
	if runtime.GOOS != "windows" || runtime.GOARCH != "amd64" {
		fmt.Fprintln(out, "ERROR: This installer must run on 64-bit Windows.")
		return 1
	}
	profile := os.Getenv("USERPROFILE")
	if profile == "" {
		fmt.Fprintln(out, "ERROR: USERPROFILE was not found.")
		return 1
	}
	target := filepath.Join(profile, "LiveWebMapMCEFDev", "thirdparty", "mcef-natives", "windows_amd64")
	if installed(target) {
		fmt.Fprintln(out, "\nChromium is already installed (required runtime files found).")
		fmt.Fprintln(out, "Location:", target)
		fmt.Fprintln(out, "No files were changed. You can launch Project Zomboid.")
		return 0
	}
	if _, err := os.Lstat(target); err == nil {
		fmt.Fprintln(out, "\nERROR: An incomplete Chromium runtime directory already exists:")
		fmt.Fprintln(out, target)
		fmt.Fprintln(out, "Nothing was replaced. Close the game and manually back up or move that directory before retrying.")
		return 1
	} else if !errors.Is(err, os.ErrNotExist) {
		fmt.Fprintln(out, "ERROR checking installation directory:", err)
		return 1
	}

	fmt.Fprintln(out, "\nThis OPTIONAL installer downloads the runtime separately from Steam Workshop.")
	fmt.Fprintln(out, "Source project: https://github.com/CinemaMod/mcef")
	fmt.Fprintln(out, "Runtime archive:", archiveURL)
	fmt.Fprintln(out, "Publisher checksum:", shaURL)
	fmt.Fprintln(out, "Install destination:", target)
	fmt.Fprintln(out, "\nThe destination retains its historical LiveWebMapMCEFDev name because")
	fmt.Fprintln(out, "that path is required by the existing compiled Java mod.")
	fmt.Fprintln(out, "\nThe installer does not request administrator rights, modify Steam/game files,")
	fmt.Fprintln(out, "change security settings, or run any downloaded executable.")
	fmt.Fprintln(out, "The downloaded Chromium DLLs/EXEs will later be used by Project Zomboid.")
	fmt.Fprintln(out, "Close Project Zomboid completely before installing.")

	if !*autoConfirm {
		fmt.Fprint(out, "\nType INSTALL to authorize download/installation (anything else cancels): ")
		response, err := input.ReadString('\n')
		if err != nil && !errors.Is(err, io.EOF) {
			fmt.Fprintln(out, "Could not read confirmation:", err)
			return 1
		}
		if strings.TrimSpace(response) != "INSTALL" {
			fmt.Fprintln(out, "Cancelled. No changes were made.")
			return 0
		}
	}

	if err := install(*offlineArchive, *providedSHA, target, out); err != nil {
		fmt.Fprintln(out, "\nINSTALLATION FAILED:", err)
		fmt.Fprintln(out, "Existing Chromium files were not intentionally replaced.")
		return 1
	}
	fmt.Fprintln(out, "\nSUCCESS! Chromium runtime installed for Zomboid Map Companion.")
	fmt.Fprintln(out, "Installed to:", target)
	fmt.Fprintln(out, "Enable ZombieBuddy and Zomboid Map Companion, then press F7 in-game.")
	return 0
}

func installed(folder string) bool {
	for _, name := range required {
		info, err := os.Stat(filepath.Join(folder, name))
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return false
		}
	}
	return true
}

func httpClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if req.URL.Scheme != "https" {
				return errors.New("refusing a non-HTTPS redirect")
			}
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			return nil
		},
	}
}

func downloadChecksum(out io.Writer) (string, error) {
	fmt.Fprintln(out, "\nDownloading publisher's SHA-256 checksum...")
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, shaURL, nil)
	if err != nil {
		return "", err
	}
	resp, err := httpClient(45 * time.Second).Do(req)
	if err != nil {
		return "", fmt.Errorf("cannot fetch publisher checksum: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("checksum server returned HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1025))
	if err != nil {
		return "", err
	}
	if len(b) > 1024 {
		return "", errors.New("publisher checksum response too long")
	}
	digest := digestPattern.FindString(string(b))
	if digest == "" {
		return "", errors.New("publisher checksum response does not contain a SHA-256 digest")
	}
	return strings.ToLower(digest), nil
}

func validDigest(digest string) bool {
	return len(digest) == 64 && digestPattern.FindStringIndex(digest) != nil &&
		len(digestPattern.FindString(digest)) == 64 && !strings.ContainsAny(digest, " \r\n\t")
}

func install(offlineArchive, suppliedSHA, target string, out io.Writer) error {
	if suppliedSHA != "" && !validDigest(suppliedSHA) {
		return errors.New("--sha256 requires exactly 64 hexadecimal characters")
	}
	digest := strings.ToLower(suppliedSHA)
	if digest == "" {
		var err error
		digest, err = downloadChecksum(out)
		if err != nil {
			return fmt.Errorf("could not verify runtime source; no files installed: %w", err)
		}
	} else {
		fmt.Fprintln(out, "\nUsing explicitly supplied SHA-256. Verify this hash independently before trusting it.")
	}

	temp, err := os.MkdirTemp("", "ZMCChromiumDownload-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temp)
	archive := filepath.Join(temp, "windows_amd64.tar.gz")
	if offlineArchive != "" {
		fmt.Fprintln(out, "Using locally supplied archive:", offlineArchive)
		if err := copyBounded(offlineArchive, archive, maxDownload); err != nil {
			return err
		}
	} else {
		if err := fetchArchive(archive, out); err != nil {
			return err
		}
	}
	calculated, err := fileDigest(archive)
	if err != nil {
		return err
	}
	fmt.Fprintln(out, "Archive SHA-256:", calculated)
	if calculated != digest {
		return fmt.Errorf("SHA-256 mismatch: expected %s; got %s", digest, calculated)
	}
	fmt.Fprintln(out, "Checksum verified. Extracting validated Chromium archive...")

	parent := filepath.Dir(target)
	if err := os.MkdirAll(parent, 0755); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(parent, ".zmc-chromium-staging-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	if err := extractRuntime(archive, stage); err != nil {
		return fmt.Errorf("archive rejected: %w", err)
	}
	native := filepath.Join(stage, "windows_amd64")
	if !installed(native) {
		return errors.New("verified archive did not contain required non-empty jcef.dll, libcef.dll and jcef_helper.exe at windows_amd64 root")
	}
	// Never remove or replace an existing target, even if it appeared mid-install.
	if _, err := os.Lstat(target); err == nil {
		return fmt.Errorf("destination now exists; refusing to overwrite: %s", target)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.Rename(native, target); err != nil {
		return fmt.Errorf("could not install verified runtime: %w", err)
	}
	receipt := filepath.Join(parent, "ZOMBOID_MAP_COMPANION_RUNTIME_SOURCE.txt")
	receiptText := "CinemaMod java-cef revision: " + revision + "\nSource: " + archiveURL + "\nSHA256: " + calculated + "\n"
	if err := os.WriteFile(receipt, []byte(receiptText), 0644); err != nil {
		fmt.Fprintln(out, "Warning: runtime installed, but provenance receipt could not be written:", err)
	}
	return nil
}

func copyBounded(src, dest string, max int64) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	if info.Size() <= 0 || info.Size() > max {
		return errors.New("local runtime archive size is invalid or exceeds 400 MiB")
	}
	out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	n, copyErr := io.Copy(out, io.LimitReader(in, max+1))
	closeErr := out.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if n == 0 || n > max {
		return errors.New("local runtime archive exceeded maximum download size")
	}
	return nil
}

// progressReader prints one line per ~25 MiB rather than a high-volume live counter.
type progressReader struct {
	reader io.Reader
	total  int64
	last   int64
	out    io.Writer
}

func (p *progressReader) Read(buf []byte) (int, error) {
	n, err := p.reader.Read(buf)
	p.total += int64(n)
	if p.total-p.last >= 25<<20 {
		fmt.Fprintf(p.out, "Downloaded %d MiB...\n", p.total>>20)
		p.last = p.total
	}
	return n, err
}

func fetchArchive(destination string, out io.Writer) error {
	fmt.Fprintln(out, "Downloading Chromium runtime from CinemaMod (may be a large download)...")
	req, err := http.NewRequest(http.MethodGet, archiveURL, nil)
	if err != nil {
		return err
	}
	resp, err := httpClient(15 * time.Minute).Do(req)
	if err != nil {
		return fmt.Errorf("runtime download failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("runtime source returned HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > maxDownload {
		return errors.New("server reported an archive larger than 400 MiB")
	}
	f, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	progress := &progressReader{reader: io.LimitReader(resp.Body, maxDownload+1), out: out}
	size, copyErr := io.Copy(f, progress)
	closeErr := f.Close()
	if copyErr != nil {
		return fmt.Errorf("runtime download interrupted: %w", copyErr)
	}
	if closeErr != nil {
		return closeErr
	}
	if size <= 0 || size > maxDownload {
		return errors.New("archive is empty or larger than 400 MiB")
	}
	fmt.Fprintf(out, "Download complete (%d MiB).\n", (size+(1<<20)-1)>>20)
	return nil
}

func fileDigest(filename string) (string, error) {
	f, err := os.Open(filename)
	if err != nil {
		return "", err
	}
	defer f.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func safeMemberName(name string) (string, error) {
	if name == "" || strings.HasPrefix(name, "/") || strings.HasPrefix(name, "\\") || strings.Contains(name, "\\") {
		return "", fmt.Errorf("invalid archive entry: %q", name)
	}
	cleaned := path.Clean(name)
	if cleaned == "." {
		return "", nil
	}
	if cleaned != "windows_amd64" && !strings.HasPrefix(cleaned, "windows_amd64/") {
		return "", fmt.Errorf("archive entry outside windows_amd64: %q", name)
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." || strings.ContainsAny(part, ":<>\"|?*\x00") {
			return "", fmt.Errorf("unsafe archive entry: %q", name)
		}
	}
	for _, part := range strings.Split(cleaned, "/") {
		if strings.TrimRight(part, ". ") != part || winReserved.MatchString(part) {
			return "", fmt.Errorf("unsafe Windows filename in archive: %q", name)
		}
		for _, r := range part {
			if r < 32 {
				return "", fmt.Errorf("control character in archive entry: %q", name)
			}
		}
	}
	return cleaned, nil
}

func extractRuntime(archivePath, stage string) error {
	f, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	seen := make(map[string]byte)
	var extracted int64
	entries := 0
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		entries++
		if entries > maxEntries {
			return errors.New("archive contains too many entries")
		}
		member, err := safeMemberName(h.Name)
		if err != nil {
			return err
		}
		if member == "" {
			continue
		}
		normalized := strings.ToLower(member)
		if kind, exists := seen[normalized]; exists {
			if h.Typeflag == tar.TypeDir && kind == tar.TypeDir {
				continue
			}
			return fmt.Errorf("duplicate archive member: %q", h.Name)
		}
		if h.Typeflag != tar.TypeDir && h.Typeflag != tar.TypeReg && h.Typeflag != tar.TypeRegA {
			return fmt.Errorf("links or special filesystem objects are not allowed: %q (type %d)", h.Name, h.Typeflag)
		}
		seen[normalized] = h.Typeflag
		dest := filepath.Join(stage, filepath.FromSlash(member))
		if h.Typeflag == tar.TypeDir {
			if err := os.MkdirAll(dest, 0755); err != nil {
				return err
			}
			continue
		}
		if h.Size < 0 || h.Size > maxUnpacked-extracted {
			return fmt.Errorf("uncompressed size limit exceeded by %q", h.Name)
		}
		extracted += h.Size
		if err := os.MkdirAll(filepath.Dir(dest), 0755); err != nil {
			return err
		}
		out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
		if err != nil {
			return err
		}
		_, err = io.CopyN(out, tr, h.Size)
		cerr := out.Close()
		if err != nil {
			return err
		}
		if cerr != nil {
			return cerr
		}
	}
	return nil
}
