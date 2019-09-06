# grafter.tabular

This library contains the deprecated tabular processing from grafter < 0.8.x.

In grafter 0.9.0 functions inside the `grafter.tabular` namespace have
been deprecated and moved into this separate project for easier
evolution of grafter.rdf.

These functions are still maintained, though it is not recommended you
start new greenfield projects with them.

NOTE: there is a maintainance release of `grafter.tabular` compatible with
grafter-2 and RDF4j.  It is available under the maven co-ordinate `grafter/grafter.tabular-2`
with an initial release of `2.1.0`.  It should be possible to run this 
alongside the old release during any transition from sesame to RDF4j.

## License

Copyright © 2014 Swirrl IT Ltd.

Distributed under the Eclipse Public License version 1.0, the same as
Clojure.
