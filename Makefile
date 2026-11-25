SDK      ?= $(HOME)/Library/Android/sdk
NDK      ?= $(SDK)/ndk/$(shell ls $(SDK)/ndk | tail -1)
ANDROID_CC = $(NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang
SWIFT_SRC  = $(shell find mac/Sources -name '*.swift') mac/Package.swift
APP        = dist/MagicPad.app

.PHONY: all app install-app run debug install uninstall clean

all: bin/magicpad bin/magicpad-grab

# Wi-Fi menu-bar app (ad-hoc signed).
app: bin/magicpad mac/Info.plist
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp mac/Info.plist $(APP)/Contents/Info.plist
	cp bin/magicpad $(APP)/Contents/MacOS/magicpad
	codesign --force --sign - --identifier app.magicpad.mac $(APP)

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
