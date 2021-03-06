[![DOI](https://zenodo.org/badge/doi/10.5281/zenodo.16110.svg)](http://dx.doi.org/10.5281/zenodo.16110)
[![Gitter](https://badges.gitter.im/Join Chat.svg)](https://gitter.im/eclipse/golo-lang?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/eclipse/golo-lang.svg?branch=master)](https://travis-ci.org/eclipse/golo-lang)

# Golo, a lightweight dynamic language for the JVM.

> The world didn't need another JVM language.
> So we built yet another one.  A simple one.

Golo is a simple dynamic, weakly-typed language for the JVM.

Built from day 1 with `invokedynamic`, Golo takes advantage of the latest advances of
the JVM. It is also a showcase on how to build a language runtime with `invokedynamic`.

Golo was originally being developed as part of the research activities of the
[DynaMid](http://dynamid.citi-lab.fr/) group of the
[CITI Laboratory](http://www.citi-lab.fr/) at
[INSA-Lyon](http://www.insa-lyon.fr/).

Golo joined the Eclipse Foundation as an incubating technology project in June 2015.

## Links

* Website: [http://golo-lang.org/](http://golo-lang.org/)
* Twitter: [@golo_lang](https://twitter.com/golo_lang)
* Eclipse PMI: [https://projects.eclipse.org/projects/technology.golo](https://projects.eclipse.org/projects/technology.golo)
* GitHub: [https://github.com/golo-lang/golo-lang](https://github.com/golo-lang/golo-lang)
* Issues: [create a new one](https://bugs.eclipse.org/bugs/enter_bug.cgi?product=Golo), or [browse all](https://bugs.eclipse.org/bugs/describecomponents.cgi?product=Golo)
* Mailing-list: [https://dev.eclipse.org/mailman/listinfo/golo-dev](https://dev.eclipse.org/mailman/listinfo/golo-dev)
* Commits + new issues mailing-list: [https://dev.eclipse.org/mailman/listinfo/golo-commit](https://dev.eclipse.org/mailman/listinfo/golo-commit)
* Wiki: [https://wiki.eclipse.org/Golo](https://wiki.eclipse.org/Golo)
* Gitter.im chats: [https://gitter.im/golo-lang/golo-lang](https://gitter.im/golo-lang/golo-lang)
* StackOverflow: [questions with the `golo-lang` tag](http://stackoverflow.com/questions/tagged/golo-lang)

## Getting Golo

Golo follows the [semantic versioning scheme](http://semver.org).

Go to the [Golo downloads page](http://golo-lang.org/download/) for general download instructions.

You can fetch Golo from Maven central under the `org.eclipse.golo` group.

We also provide [Docker](http://docker.com/) images based on Ubuntu 14.04 and Java SE 8:
`docker pull jponge/golo-lang` ([https://registry.hub.docker.com/u/jponge/golo-lang/](https://registry.hub.docker.com/u/jponge/golo-lang/))

## Building Golo

### Dependencies

Golo is built with [Apache Maven](http://maven.apache.org/). We suggest that you use
[Rake](http://rake.rubyforge.org), too. The provided `Rakefile` acts as a frontend
to the Maven build and simplifies some invocations.

Golo needs Java SE 8 or more to build and run.

The Maven build is self-contained, including the documentation generation.

### Building from sources

If this is the first time you are building Golo on a machine, you need to boostrap a subset
of Golo, compile its Maven plugin and finally rebuild it all. Fortunately this is as simple
as:

    rake special:bootstrap

Once this is done you can use the common `rebuild`, `clean` or `test:all` tasks. All tasks
can be listed using:

    rake -T

Of course you can just call Maven directly if you like.

## License

    Copyright (c) 2012-2015 Institut National des Sciences Appliquées de Lyon (INSA-Lyon) and contributors

    All rights reserved. This program and the accompanying materials
    are made available under the terms of the Eclipse Public License v1.0
    which accompanies this distribution, and is available at
    http://www.eclipse.org/legal/epl-v10.html

## Contributing

We welcome contributions from the community!

Check the `CONTRIBUTING.md` file for instructions.
