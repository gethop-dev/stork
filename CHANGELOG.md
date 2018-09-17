# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.1.5] - 2018-09-17

## Added

- Before running tests, sourcecode will also be Linted.

## [0.1.4] - 2018-09-17

## Fixed

- Datomic's reader macro #db/fn was breaking uberjar creation of the project that depends Stork.
Changing that to `d/function` did the trick.

## [0.1.3] - 2018-09-17

## Added

- Travis CI integration
- Automatic deployments to Clojars

[Unreleased]: https://github.com/magnetcoop/stork/compare/0.1.3...HEAD
[0.1.3]: https://github.com/magnetcoop/stork/compare/0.1.0...0.1.3
