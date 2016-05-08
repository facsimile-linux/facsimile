# Facsimile
Snapshot backup system

[![Build Status](https://travis-ci.org/facsimile-linux/facsimile.svg?branch=master)](https://travis-ci.org/facsimile-linux/facsimile)
[![Coverage Status](https://coveralls.io/repos/facsimile-linux/facsimile/badge.svg?branch=master&service=github)](https://coveralls.io/github/facsimile-linux/facsimile?branch=master)

## Development

### New work / bug fixes

Each commit should include an addition or change to a line in the `debian/changelog` file. Running `dch` will set this up for you in an editor.

#### Ubuntu Versions

* Trusty - comes with Scala 2.9.2
* Vivid - comes with Scala 2.9.2
* Wily - comes with Scala 2.11.6
* Xenial - comes with Scala 2.11.6

Will need to bundle with Scala libraries for Trusty / Vivid, and require that Scala is installed and not bundle for Wily / Xenial.

To ignore scala classes while assembling fat jar, do: `packExcludeJars := Seq("scala-.*\\.jar"),`

https://wiki.debian.org/Java/Packaging

Perhaps use a Maven build rather than SBT - https://wiki.debian.org/Java/MavenBuilder

### Publishing

* To create a new release candidate branch and release:
  * `git stash`
  * `git checkout master`
  * `git pull --rebase`
  * `dch --release --distribution unstable # update version number to concrete version`
  * `git add debian/changelog && git commit -m "Release version <release version>"`
  * `git checkout -b <new candidate release branch identifier>`
  * `git tag <release version>`
  * `git push origin <new candidate release branch identifier> --tags`
  * `git checkout master`
  * `dch --newversion <next version, like 1.3.x-1ubuntu1> # add dummy comment and save`
  * `git add debian/changelog && git commit -m "Prepare for next development iteration"`
  * `git push origin master`
  * `git stash pop`
* To create patch of existing release series:
  * `git stash`
  * `git checkout <release branch>`
  * `git cherry-pick -x <sha> # do this repeatedly for shas to integrate`
  * `dch --release # update version number to concrete version`
  * `git add debian/changelog && git commit -m "Release version <release version>"`
  * `git tag <release version>`
  * `git push origin <new candidate release branch identifier> --tags`
  * `git checkout master`
  * `git stash pop`
* To release a new major version
  * `./makedeb-src.sh <version>`
    * This will create and upload source packages to [the repository in Launchpad](https://launchpad.net/~track16/+archive/ubuntu/ppa/+packages). Note that the version specified must be a git reference (preferrably a tag).
