#!/usr/bin/env bash

set -e

if [ -z "$1" ]; then echo "package name is unset"; exit 1; fi
if [ -z "$2" ]; then echo "version is unset"; exit 1; fi

RELEASES="trusty"

PKGNAME=$
PKGVER=$2
TMP=$(mktemp -d)
CURRENT=$(pwd)

git stash

git checkout $PKGVER

for release in ${RELEASES}; do
	echo ""
	echo "${PKGNAME} ${PKGVER} ${release}"
	echo ""
	DST=${TMP}/${PKGNAME}-${PKGVER}~${release}/
	mkdir ${DST}
	cp -aR ${CURRENT}/* ${DST}
	cd ${DST}
	mv debian/${PKGNAME} debian-${PKGNAME}
	rm -rf debian
	mv debian-${PKGNAME} debian

        #debian: changelog
	sed -e "s/${PKGNAME} (.*)/${PKGNAME} (${PKGVER}~${release})/g" -e "s/unstable;/${release};/g" -i debian/changelog
	
	# build libraries
	activator clean pack

	debuild -i -S

	dput ppa:track16/ppa ${TMP}/facsimile*${release}_source.changes
done

rm -rf ${TMP}

cd ${CURRENT}

git checkout master

git stash pop
