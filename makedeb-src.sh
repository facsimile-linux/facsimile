#!/usr/bin/env bash

set -e

if [ -z "$1" ]; then echo "version is unset"; exit 1; fi

RELEASES="trusty"

PKGNAME=facsimile
PKGVER=$1
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

        #debian: changelog
	sed -e "s/${PKGNAME} (.*)/${PKGNAME} (${PKGVER}~${release})/g" -e "s/unstable;/${release};/g" -i debian/changelog

	debuild -i -S

	dput ppa:track16/ppa ${TMP}/facsimile*${release}_source.changes
done

rm -rf ${TMP}

cd ${CURRENT}

git checkout master

git stash pop
