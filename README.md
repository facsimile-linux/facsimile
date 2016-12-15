# Facsimile
Snapshot backup system

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
  * `previousreleasesha=$(git log --format=format:%H -n 1 --grep="Release version")`
  * `changes=$(git log --format=%B $previousreleasesha..HEAD)`
  * `dch --changelog debian/facsimile/changelog --newversion <next minor version, like 1.3.0-1ubuntu1> "$changes"`
  * `dch --release --distribution unstable`
    * Group commits into changes appropriate for a changelist
  * `cp debian/facsimile/changelog debian/facsimile-toolbar/changelog`
  * `git add debian/facsimile/changelog debian/facsimile-toolbar/changelog && git commit -m "Release version <release version>"`
  * `git checkout -b <new candidate release branch identifier>`
  * `git tag <release version>`
  * `git push origin <new candidate release branch identifier> --tags`
  * `git checkout master`
  * `git stash pop`
* To create patch of existing release series:
  * `git stash`
  * `git checkout <release branch>`
  * `git cherry-pick -x <sha>`
    * Do this repeatedly for shas to integrate
  * `previousreleasesha=$(git log --format=format:%H -n 1 --grep="Release version")`
  * `changes=$(git log --format=%B $previousreleasesha..HEAD)`
  * `git checkout master`
  * `dch --changelog debian/facsimile/changelog --newversion <next minor version, like 1.3.0-1ubuntu1> "$changes"`
  * `dch --release --distribution unstable`
    * Group commits into changes appropriate for a changelist
  * `cp debian/facsimile/changelog debian/facsimile-toolbar/changelog`
  * `git add debian/facsimile/changelog debian/facsimile-toolbar/changelog && git commit -m "Release version <release version>"`
  * `lastcommit=$(git rev-list HEAD -n 1)`
  * `git checkout <release branch>`
  * `git cherry-pick -x $lastcommit`
  * `git tag <release version>`
  * `git push origin <new candidate release branch identifier> --tags`
  * `git checkout master`
  * `git stash pop`
* To release a new major version
  * `./makedeb-src.sh <package> <version>`
    * This will create and upload source packages to [the repository in Launchpad](https://launchpad.net/~track16/+archive/ubuntu/ppa/+packages). Note that the version specified must be a git reference (preferrably a tag).
    
### TODO

* Maintain system-wide encfsctl process for encrypting / decrypting pathnames on the fly (for speed increase)
* Build local, fixed path mode (link-dest-based)
 * Proper config load / save
 * Backup
* Build local, whole disk mode (link-dest-based)
 * Proper config load / save
 * Backup
