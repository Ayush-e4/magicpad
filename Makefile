SDK      ?= $(HOME)/Library/Android/sdk
NDK      ?= $(SDK)/ndk/$(shell ls $(SDK)/ndk | tail -1)
ANDROID_CC = $(NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang
SWIFT_SRC  = $(shell find mac/Sources -name '*.swift') mac/Package.swift
APP        = dist/MagicPad.app
# Signs with the local certificate from `make signing-cert` when it exists, so macOS keeps
# the Accessibility permission across rebuilds. Without it, falls back to ad-hoc (-).
SIGN_NAME  = MagicPad Local Signing
SIGN_ID   ?= $(shell security find-identity -p codesigning 2>/dev/null | grep -q '"$(SIGN_NAME)"' && echo '$(SIGN_NAME)' || echo -)

.PHONY: all app install-app signing-cert run debug install uninstall clean

all: bin/magicpad bin/magicpad-grab

# Wi-Fi menu-bar app.
app: bin/magicpad mac/Info.plist
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp mac/Info.plist $(APP)/Contents/Info.plist
	cp bin/magicpad $(APP)/Contents/MacOS/magicpad
	codesign --force --sign "$(SIGN_ID)" --identifier app.magicpad.mac $(APP)

signing-cert:
	mac/make-signing-cert.sh "$(SIGN_NAME)"

install-app: app
	rm -rf ~/Applications/MagicPad.app
	mkdir -p ~/Applications
	cp -R $(APP) ~/Applications/MagicPad.app
	open ~/Applications/MagicPad.app

bin/magicpad-grab: phone/grab.c
	@mkdir -p bin
	$(ANDROID_CC) -O2 -Wall -Wextra -o $@ $<

bin/magicpad: $(SWIFT_SRC)
	@mkdir -p bin
	cd mac && swift build -c release
	cp mac/.build/release/magicpad bin/magicpad

run: all
	./bin/magicpad run

debug: all
	./bin/magicpad run --debug

install: all
	./bin/magicpad install

uninstall:
	./bin/magicpad uninstall

clean:
	rm -rf bin mac/.build dist
