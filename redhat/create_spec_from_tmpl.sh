#!/bin/sh
DATE=`date "+%Y%m%d%H%M"`
COMMIT=`git rev-parse HEAD`
SHORTCOMMIT=`git rev-parse --short=8 HEAD`

cp na2-exec-plugin.spec.tmpl na2-exec-plugin.spec

sed -i -e "s;@@DATE@@;${DATE};" na2-exec-plugin.spec
sed -i -e "s;@@COMMIT@@;${COMMIT};" na2-exec-plugin.spec
sed -i -e "s;@@SHORTCOMMIT@@;${SHORTCOMMIT};" na2-exec-plugin.spec
