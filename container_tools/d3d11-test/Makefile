# Fable's Direct3D 11 container tool, cross-compiled for Windows x86_64 with MinGW-w64
# (Debian/Ubuntu: apt install g++-mingw-w64-x86-64).
#
#   make          builds build/d3d11-test.exe
#   make clean
#
# The release workflow copies the result to app/src/main/assets/container_tools/, from where
# ContainerTools installs it into every container as C:\fable\tools\d3d11-test.exe.

CROSS ?= x86_64-w64-mingw32-
CXX := $(CROSS)g++

CXXFLAGS ?= -O2 -Wall -Wextra
override CXXFLAGS += -std=c++17 -fno-exceptions -fno-rtti
# GUI subsystem, wWinMain, and libgcc/libstdc++/winpthread linked in statically: the .exe needs
# no DLL beyond the ones Windows (Wine) and Direct3D (DXVK) provide.
override LDFLAGS += -mwindows -municode -static -static-libgcc -static-libstdc++ -s
LDLIBS := -ld3d11

build/d3d11-test.exe: src/main.cpp Makefile
	@mkdir -p $(@D)
	$(CXX) $(CXXFLAGS) -o $@ $< $(LDFLAGS) $(LDLIBS)

clean:
	rm -rf build

.PHONY: clean
