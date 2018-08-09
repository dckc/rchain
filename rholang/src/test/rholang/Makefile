# USAGE: scala link.scala <libraryDirectory> <rholangSource> [<rholangSource> ...]
RHOLINK=scala ../../../examples/linking/link.scala
# RHOLINK=node ~/projects/rchain.cloud/linking/target/scala-2.12/rholang-linker-fastopt.js

check: happyPath1.rho.linked
	rnode eval $<

happyPath1.rho.linked: happyPath1.rho CrossShard.rho MockRegistry.rho
	$(RHOLINK) . $<

CrossShard.rho: ../../main/rholang/cross-shard.rho cross-shard-wrap.patch
	cp $< $@
	patch -i cross-shard-wrap.patch $@

clean:
	rm -f CrossShard.rho happyPath1.rho.linked
