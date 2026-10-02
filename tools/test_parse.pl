use strict; use warnings;
my $file = shift or die "usage: test_parse.pl <values.xml>";
open my $F, '<', $file or die $!; local $/; my $x = <$F>; close $F;
$x =~ s/<!--.*?-->//gs;
return unless $x =~ /<resources[^>]*>/;
my $body = substr($x, $+[0], rindex($x, '</resources>') - $+[0]);
my @out; my $i = 0;
while ($i < length $body) {
    my $c = substr($body, $i, 1);
    if ($c ne '<') { $i++; next; }
    substr($body, $i) =~ /^<([A-Za-z0-9_]+)/ or $i++, next;
    my $tag = $1;
    my ($close, $k) = (undef, $i);
    while ($k < length $body) {
        my $ch = substr($body, $k, 1);
        if ($ch eq '"') { my $q = index($body, '"', $k+1); last if $q < 0; $k = $q + 1; next; }
        if ($ch eq '>') { $close = $k; last; }
        $k++;
    }
    last unless defined $close;
    my $head = substr($body, $i, $close - $i + 1);
    if ($head =~ m{/>\s*$}) { push @out, substr($body, $i, $close - $i + 1); $i = $close + 1; next; }
    my $rest = substr($body, $close + 1);
    last unless $rest =~ m{</$tag\s*>};
    my $end = $close + 1 + $+[0];
    push @out, substr($body, $i, $end - $i);
    $i = $end;
}
@out = grep { /\S/ } @out;
print "top-level elements: " . scalar(@out) . "\n";
for my $probe (@ARGV[1..$#ARGV]) {
    my ($hit) = grep { /\bname="\Q$probe\E"/ } @out;
    print "$probe: " . (defined $hit ? "FOUND" : "MISSING") . "\n";
}
