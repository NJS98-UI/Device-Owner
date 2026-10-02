use strict; use warnings;
my $file = shift or die "usage: dbg_parse.pl <values.xml>";
open my $F, '<', $file or die $!; local $/; my $x = <$F>; close $F;
$x =~ s/<!--.*?-->//gs;
$x =~ /<resources[^>]*>/ or die "no resources root";
my $body = substr($x, $+[0], rindex($x, '</resources>') - $+[0]);
print "body len: ", length($body), "\n";
my @out; my $i = 0;
while ($i < length $body) {
    my $c = substr($body, $i, 1);
    if ($c ne '<') { $i++; next; }
    if (substr($body, $i) !~ /^<([A-Za-z0-9_]+)/) {
        print "no tag at $i: ", substr($body, $i, 80), "\n"; $i++; next;
    }
    my $tag = $1;
    my ($close, $k); $k = $i;
    while ($k < length $body) {
        my $ch = substr($body, $k, 1);
        if ($ch eq '"') {
            my $q = index($body, '"', $k + 1);
            if ($q < 0) { print "unterminated quote at $k\n"; $close = -1; last; }
            $k = $q + 1; next;
        }
        if ($ch eq '>') { $close = $k; last; }
        $k++;
    }
    if (!defined $close || $close < 0) { print "no > for tag $tag at $i\n"; last; }
    my $head = substr($body, $i, $close - $i + 1);
    if ($head =~ m{/>\s*$}) { push @out, substr($body, $i, $close - $i + 1); $i = $close + 1; next; }
    my $rest = substr($body, $close + 1);
    if ($rest !~ m{</$tag\s*>}) {
        print "no </$tag> after element at $i; head=", substr($head, 0, 100), "\n  rest=", substr($rest, 0, 100), "\n";
        last;
    }
    my $end = $close + 1 + $+[0];
    push @out, substr($body, $i, $end - $i);
    $i = $end;
}
print "parsed: ", scalar(@out), "\n";
my %by;
for my $el (@out) { my ($t) = $el =~ /^<([A-Za-z0-9_]+)/; $by{$t // '?'}++; }
my @tags = sort { $by{$b} <=> $by{$a} } keys %by;
print "$_: $by{$_}\n" for @tags;
