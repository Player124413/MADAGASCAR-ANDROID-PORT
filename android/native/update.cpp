#include "core/log.h"

// The Switch menu shares the Android settings panel. Updates are distributed as separate signed APKs for now;
// the button remains a harmless reminder until this port has a release channel.
namespace host {
void CheckForUpdates() { LOGI("updates: install a newer Madagascar Android APK manually"); }
}  // namespace host
