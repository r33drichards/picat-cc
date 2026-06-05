WASM = third_party/picat/emu/picat.wasm
RES  = engine/src/main/resources/picat

.PHONY: wasm resources clean

wasm:
	$(MAKE) -C third_party/picat/emu -f Makefile.wasm picat.wasm

resources: wasm
	mkdir -p $(RES)/lib
	cp $(WASM) $(RES)/picat.wasm
	cp third_party/picat/lib/*.pi $(RES)/lib/
clean:
	$(MAKE) -C third_party/picat/emu -f Makefile.wasm clean || true
	rm -f $(RES)/picat.wasm
	rm -rf $(RES)/lib
