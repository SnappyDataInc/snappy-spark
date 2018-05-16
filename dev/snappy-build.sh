#!/bin/sh

flags="-Pyarn -Phive-thriftserver -Phadoop-2.7 -Dhadoop.version=2.7.3"

if [ -z "$1" ]; then
  ./build/mvn $flags -DskipTests package
else
  ./build/mvn $flags "$@"
fi
