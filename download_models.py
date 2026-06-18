# Downloads the embedding model assets that used to be stored in Git LFS.
#
# These models are intentionally NOT committed to the repository (see the
# `/resources/models/*` rule in .gitignore). Fetching them from the upstream
# source keeps the repo small and avoids Git LFS bandwidth limits entirely.
#
# Source: Qdrant FastEmbed ONNX ports on the Hugging Face Hub. Each file is
# pinned to an exact repo revision, and the large blobs are checksum-verified
# against the SHA-256 they had when they lived in LFS, so downloads are
# reproducible and tamper-evident.
#
# Usage:
#   python download_models.py            # fetch anything missing/incomplete
#   python download_models.py --force    # re-download everything
#
# Runs with the Python standard library only (no pip dependencies). It is also
# invoked automatically by `cargo make debug|release` before the build.

import argparse
import hashlib
import sys
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
MODELS_DIR = REPO_ROOT / "resources" / "models"
HF = "https://huggingface.co"
CHUNK = 1 << 20  # 1 MiB

# Each model maps a local directory to a pinned Hugging Face revision.
# files: (local_name, remote_name, sha256 | None)
#   - local_name differs from remote_name where upstream uses another name
#     (the quantized model ships as "model_optimized.onnx").
#   - sha256 is pinned only for the large former-LFS blobs; the small JSON
#     metadata is left unpinned so harmless upstream metadata tweaks don't
#     break setup.
MODELS = [
    {
        "dir": "all-MiniLM-L6-v2",
        "repo": "Qdrant/all-MiniLM-L6-v2-onnx",
        "rev": "5f1b8cd78bc4fb444dd171e59b18f3a3af89a079",
        "files": [
            ("model.onnx", "model.onnx",
             "bbd7b466f6d58e646fdc2bd5fd67b2f5e93c0b687011bd4548c420f7bd46f0c5"),
            ("config.json", "config.json", None),
            ("tokenizer.json", "tokenizer.json", None),
            ("tokenizer_config.json", "tokenizer_config.json", None),
            ("special_tokens_map.json", "special_tokens_map.json", None),
            ("vocab.txt", "vocab.txt", None),
        ],
    },
    {
        "dir": "paraphrase-multilingual-MiniLM-L12-v2-onnx-Q",
        "repo": "Qdrant/paraphrase-multilingual-MiniLM-L12-v2-onnx-Q",
        "rev": "faf4aa4225822f3bc6376869cb1164e8e3feedd0",
        "files": [
            ("model.onnx", "model_optimized.onnx",
             "634d0f66c29dc934c8fa72b8a4fe91dd4d420a22f1d82a241058d4316e659a99"),
            ("tokenizer.json", "tokenizer.json",
             "fa685fc160bbdbab64058d4fc91b60e62d207e8dc60b9af5c002c5ab946ded00"),
            ("unigram.json", "unigram.json",
             "da145b5e7700ae40f16691ec32a0b1fdc1ee3298db22a31ea55f57a966c4a65d"),
            ("config.json", "config.json", None),
            ("tokenizer_config.json", "tokenizer_config.json", None),
            ("special_tokens_map.json", "special_tokens_map.json", None),
            ("ort_config.json", "ort_config.json", None),
        ],
    },
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def is_present(dest: Path, expected_sha: str | None) -> bool:
    """True when dest already holds the wanted file."""
    if not dest.is_file() or dest.stat().st_size == 0:
        return False
    if expected_sha is None:
        return True
    return sha256(dest) == expected_sha


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "jarvis-model-fetch"})
    with urllib.request.urlopen(req) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        done = 0
        with tmp.open("wb") as out:
            while True:
                buf = resp.read(CHUNK)
                if not buf:
                    break
                out.write(buf)
                done += len(buf)
                if total:
                    pct = done * 100 // total
                    print(f"\r    {pct:3d}%  {done >> 20:>5d}/{total >> 20} MiB",
                          end="", flush=True)
        if total:
            print()
    tmp.replace(dest)


def main() -> int:
    parser = argparse.ArgumentParser(description="Download Jarvis embedding models.")
    parser.add_argument("--force", action="store_true",
                        help="re-download even if files already exist")
    args = parser.parse_args()

    failures = []
    for model in MODELS:
        target = MODELS_DIR / model["dir"]
        print(f"[*] {model['dir']}  <-  {model['repo']}@{model['rev'][:10]}")
        for local_name, remote_name, expected_sha in model["files"]:
            dest = target / local_name
            if not args.force and is_present(dest, expected_sha):
                print(f"    skip  {local_name} (present)")
                continue
            url = f"{HF}/{model['repo']}/resolve/{model['rev']}/{remote_name}"
            print(f"    get   {local_name}")
            try:
                download(url, dest)
            except (urllib.error.URLError, OSError) as e:
                print(f"    FAIL  {local_name}: {e}")
                failures.append(f"{model['dir']}/{local_name}")
                continue
            if expected_sha is not None:
                actual = sha256(dest)
                if actual != expected_sha:
                    print(f"    FAIL  {local_name}: sha256 mismatch\n"
                          f"          expected {expected_sha}\n"
                          f"          actual   {actual}")
                    dest.unlink(missing_ok=True)
                    failures.append(f"{model['dir']}/{local_name}")

    if failures:
        print(f"\n[!] {len(failures)} file(s) failed: {', '.join(failures)}",
              file=sys.stderr)
        return 1
    print("\n[+] All models present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
