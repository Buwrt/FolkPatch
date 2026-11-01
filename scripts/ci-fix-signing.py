"""Temp CI helper: make the debug variant use the committed keystore."""
p = 'app/build.gradle.kts'
s = open(p).read()
marker = '        debug {'
add = '            signingConfig = signingConfigs.getByName("release")'
i = s.index(marker)
j = s.index('\n', i) + 1
if add not in s:
    s = s[:j] + add + '\n' + s[j:]
    open(p, 'w').write(s)
    print('patched debug signingConfig')
else:
    print('already patched')
