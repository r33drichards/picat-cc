{
  description = "picat-cc: Picat FFI for ComputerCraft";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f s);
      wasiArch = {
        "aarch64-darwin" = "arm64-macos";
        "x86_64-linux" = "x86_64-linux";
      };
      # Fill in after first `nix develop` attempt reports the real hashes.
      wasiHash = {
        "aarch64-darwin" = "sha256-4eUp6iJrHbC0MDJ4Cd6ukka1gPo8rjLTHILf53AjNYc=";
        "x86_64-linux" = nixpkgs.lib.fakeHash; # TODO: fill on linux
      };
    in {
      devShells = forAll (system:
        let
          pkgs = import nixpkgs { inherit system; };
          wasiSdk = pkgs.stdenvNoCC.mkDerivation rec {
            pname = "wasi-sdk";
            version = "25.0";
            src = pkgs.fetchurl {
              url = "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-25.0-${wasiArch.${system}}.tar.gz";
              hash = wasiHash.${system};
            };
            dontStrip = true;
            dontPatchShebangs = true;
            # prebuilt clang toolchain; on Linux the ELF binaries need patching
            nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.autoPatchelfHook ];
            buildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];
            installPhase = ''
              mkdir -p $out
              cp -r . $out/
            '';
          };
        in {
          default = pkgs.mkShell {
            packages = [ pkgs.jdk21 pkgs.gradle pkgs.gnumake ];
            WASI_SDK_PATH = "${wasiSdk}";
            JAVA_HOME = "${pkgs.jdk21}";
          };
        });
    };
}
