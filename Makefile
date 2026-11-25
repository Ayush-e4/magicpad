SDK      ?= $(HOME)/Library/Android/sdk
NDK      ?= $(SDK)/ndk/$(shell ls $(SDK)/ndk | tail -1)
ANDROID_CC = $(NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang
SWIFT_SRC  = $(wildcard mac/Sources/MagicPad/*.swift) mac/Package.swift

.PHONY: all run debug install uninstall clean

all: bin/magicpad bin/magicpad-grab

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
	rm -rf bin mac/.build
