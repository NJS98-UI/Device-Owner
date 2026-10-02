BEGIN { want = 0; c = 0 }
/^[0-9a-f]+:  *\|\[[0-9a-f]+\] / {
  m = $3
  want = 0
  if (m ~ pat) { want = 1; c = 0; print "=== " m }
  next
}
want {
  line = $0
  sub(/^[0-9a-f]+: +[0-9a-f ]+ +\|/, "", line)
  gsub(/ +\/\/ (method|field|string|type)@[0-9a-f]+/, "", line)
  gsub(/ +$/, "", line)
  if (line ~ /const-string|const\/|const v|invoke|iput|iget|sget|sput|return|if-|goto|move-/) {
    printf "%4d %s\n", ++c, line
  }
  if (c > 300) { want = 0 }
}
