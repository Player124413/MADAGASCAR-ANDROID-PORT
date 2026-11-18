// Finds the user's extracted game files or Xbox disc image in SDL's app-specific storage. The XBE must be
// byte-for-byte the same executable used to generate this build; neither the game nor generated code is distributed.
#include <SDL3/SDL.h>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "app/app.h"
#include "core/log.h"
#include "image_symbols.h"
#include "kernel/crypto.h"
#include "kernel/disc.h"

extern const char kImageSha1[];

namespace app {
namespace {

namespace fs = std::filesystem;

std::string Sha1(const std::vector<uint8_t>& data) {
    alignas(8) uint8_t ctx[128] = {};
    uint8_t digest[20] = {};
    crypto::ShaInit(ctx);
    crypto::ShaUpdate(ctx, data.data(), data.size());
    crypto::ShaFinal(ctx, digest);
    std::string hex;
    hex.reserve(40);
    for (uint8_t b : digest) {
        hex += "0123456789abcdef"[b >> 4];
        hex += "0123456789abcdef"[b & 15];
    }
    return hex;
}

bool ReadHostFile(const fs::path& path, std::vector<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    std::streamoff size = f.tellg();
    if (size <= 0 || size > 64 * 1024 * 1024) return false;
    out.resize(size_t(size));
    f.seekg(0);
    return bool(f.read(reinterpret_cast<char*>(out.data()), size));
}

bool IsImage(const fs::path& path) {
    std::string ext = path.extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    return ext == ".iso" || ext == ".xiso";
}

bool Consider(GameFiles& game, const fs::path& folder, const fs::path& label, std::vector<uint8_t> xbe,
              std::unique_ptr<kernel::Disc> disc) {
    const std::string sha = Sha1(xbe);
    const bool matches = sha == kImageSha1;
    LOGI("game: %s, XBE SHA-1 %s%s", label.string().c_str(), sha.c_str(), matches ? "" : " (wrong build revision)");
    if (!matches) return false;
    game = {folder, std::move(xbe)};
    kernel::MountDisc(std::move(disc));
    return true;
}

void MissingMessage(const std::string& detail) {
    const char* title = "Madagascar Android";
    std::string message = detail + "\n\nUse the launcher to import an Xbox ISO or an extracted folder containing default.xbe.";
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, title, message.c_str(), nullptr);
}

}  // namespace

bool FindGame(GameFiles& game) {
    const fs::path root = ExeDir();
    const std::vector<fs::path> folders = {root / "game", root / "assets"};
    std::vector<fs::path> images;
    std::error_code ec;
    std::string wrongRevision;

    // Extracted files first: their XBE and the game's other files live together under <app files>/game.
    for (const fs::path& folder : folders) {
        std::vector<uint8_t> xbe;
        if (ReadHostFile(folder / IMAGE_XBE, xbe)) {
            if (Consider(game, folder, folder / IMAGE_XBE, std::move(xbe), nullptr)) return true;
            wrongRevision = (folder / IMAGE_XBE).string();
        }
        ec.clear();
        for (fs::directory_iterator it(folder, ec), end; !ec && it != end; it.increment(ec)) {
            if (it->is_regular_file(ec) && IsImage(it->path())) images.push_back(it->path());
        }
    }
    std::sort(images.begin(), images.end());

    for (const fs::path& image : images) {
        auto disc = std::make_unique<kernel::Disc>();
        std::string error;
        if (!disc->Open(image, error)) {
            LOGW("game: %s: %s", image.string().c_str(), error.c_str());
            continue;
        }
        std::vector<uint8_t> xbe;
        if (!disc->Load(IMAGE_XBE, xbe)) {
            LOGW("game: %s has no %s", image.string().c_str(), IMAGE_XBE);
            continue;
        }
        if (Consider(game, image.parent_path(), image, std::move(xbe), std::move(disc))) return true;
        wrongRevision = image.string();
    }

    if (!wrongRevision.empty()) {
        LOGE("game: %s does not match the XBE used for this build", wrongRevision.c_str());
        MissingMessage("The selected game uses a different Madagascar XBE revision than this app was built for.");
    } else {
        LOGE("game: no default.xbe or Xbox ISO was found under %s", root.string().c_str());
        MissingMessage("No Madagascar game files were found in this app's game folder.");
    }
    return false;
}

}  // namespace app
