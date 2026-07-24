
ABIS_SIMPLE= x86 x86_64 armeabi-v7a arm64-v8a

all: build-all

build-all: \
	submodules \
	apply-patches \
	build-aap-core \
	create-local-prop \
	build-java

## Submodules and patches

submodules:
	git submodule update --init --recursive

# Our Android support for choc::ui::WebView and for cmajor's LLVM/AAP backends lives as
# uncommitted patches against the cmajor and choc submodules (see android-*.patch at the
# top of this repo) rather than as commits, since we don't control those repos. Applying
# them is idempotent - if a patch is already applied, we just skip it.
apply-patches:
	@if git -C external/cmajor/include/choc apply --reverse --check $(CURDIR)/android-choc-webview.patch 2>/dev/null ; then \
		echo "android-choc-webview.patch already applied" ; \
	else \
		echo "Applying android-choc-webview.patch to external/cmajor/include/choc" ; \
		git -C external/cmajor/include/choc apply $(CURDIR)/android-choc-webview.patch ; \
	fi
	@if git -C external/cmajor apply --reverse --check $(CURDIR)/android-cmajor.patch 2>/dev/null ; then \
		echo "android-cmajor.patch already applied" ; \
	else \
		echo "Applying android-cmajor.patch to external/cmajor" ; \
		git -C external/cmajor apply $(CURDIR)/android-cmajor.patch ; \
	fi

build-aap-core:
	if [ ! -f external/aap-core/local.properties ] ; then \
		if [ `uname` == "Darwin" ] ; then \
			echo "sdk.dir=$(HOME)/Library/Android/sdk" > external/aap-core/local.properties ; \
		else \
			echo "sdk.dir=$(HOME)/Android/Sdk" > external/aap-core/local.properties ; \
		fi ; \
	fi
	cd external/aap-core && ./gradlew publishToMavenLocal

## Build utility

create-local-prop:
	if [ ! -f local.properties ] ; then \
		if [ `uname` == "Darwin" ] ; then \
			echo "sdk.dir=$(HOME)/Library/Android/sdk" > local.properties ; \
		else \
			echo "sdk.dir=$(HOME)/Android/Sdk" > local.properties ; \
		fi ; \
	fi

build-java: create-local-prop
	ANDROID_SDK_ROOT=$(ANDROID_SDK_ROOT) ./gradlew build
 
