/^  Class descriptor  : / {
  cls = $NF
  gsub(/[^A-Za-z0-9_$.]/, "", cls)
  gsub(/^L/, "", cls)
  next
}
/^[0-9a-f]+:  *\|\[[0-9a-f]+\] / {
  m = $3
  next
}
/#int [0-9]+ / {
  if (match($0, /#int [0-9]+/)) {
    v = substr($0, RSTART + 5, RLENGTH - 5) + 0
    if (v >= 100 && v <= 400) ints[m] = ints[m] " " v
  }
  next
}
/send:\(III\)V|sendItemValue|sendItemValues|sendCarSettingValue|sendCarSettingValues/ {
  if (m != "") print cls " :: " m " ==>" ints[m]
}
