#!/usr/bin/env bash

set -e

if [ -z "$1" ]; then echo "package name is unset"; exit 1; fi
if [ -z "$2" ]; then echo "version is unset"; exit 1; fi

PKGNAME=$1
PKGVER=$2
CURRENT=$(pwd)

declare -A RELEASES
declare -A PACKAGE_SCALA

RELEASES=( ["precise"]="1.6" ["trusty"]="1.7" ["xenial"]="1.8" ["yakkety"]="1.8")
PACKAGE_SCALA=( ["precise"]="yes" ["trusty"]="yes" ["xenial"]="no" ["yakkety"]="no")

git stash

branch=`git rev-parse --abbrev-ref HEAD`

git checkout $PKGVER

for release in "${!RELEASES[@]}"; do
    TMP=$(mktemp -d)
    java_version=${RELEASES[$release]}
	echo ""
	echo "${PKGNAME} ${PKGVER} ${release} - Java $java_version"
	echo ""
	DST=${TMP}/${PKGNAME}-${PKGVER}~${release}/
	mkdir ${DST}
	cp -aR ${CURRENT}/* ${DST}
	pushd ${DST}
	pwd
	mv debian/${PKGNAME} debian-${PKGNAME}
	rm -rf debian
	mv debian-${PKGNAME} debian

    #debian: changelog
	sed -e "s/${PKGNAME} (.*)/${PKGNAME} (${PKGVER}~${release})/g" -e "s/unstable;/${release};/g" -i debian/changelog
	# jvm version
	sed -e "s/target:jvm-1.8/target:jvm-$java_version/g" -i build.sbt
	# package scala
	if [ "${PACKAGE_SCALA[$release]}" == "no" ]; then
	  echo "Not packaging Scala for $release"
	  echo 'packExcludeJars := Seq("hawtjni-runtime.*\\.jar","jansi.*\\.jar","jline.*\\.jar","scala-actors.*\\.jar","scala-compiler.*\\.jar","scala-library.*\\.jar","scala-parser-combinators.*\\.jar","scalap.*\\.jar","scala-reflect.*\\.jar","scala-xml.*\\.jar")' >> build.sbt
	  sed -e "s/default-jre,/default-jre, scala-library,/g" -i debian/control
	  mv src/main/shell/facsimile-scala src/main/shell/facsimile
	else
      mv src/main/shell/facsimile-java src/main/shell/facsimile
    fi
	
	# build libraries
	activator clean pack

	debuild -i -S
	dput ppa:track16/ppa ${TMP}/facsimile*${release}_source.changes

    rm -rf ${TMP}

	popd
done

git checkout $branch

git stash pop
