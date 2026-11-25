#!/usr/bin/env ruby
# Parse every workflow and local action without executing YAML or installing gems.
require "psych"

root = File.expand_path(ARGV.fetch(0, ".."), ARGV.empty? ? __dir__ : Dir.pwd)
paths = Dir.glob([
  File.join(root, ".github/workflows/*.{yml,yaml}"),
  File.join(root, ".github/actions/**/action.{yml,yaml}")
]).sort
abort "CI YAML: no workflow definitions" if paths.empty?
errors = 0
paths.each do |path|
  # Parse the AST rather than loading or instantiating YAML objects.
  begin
    documents = Psych.parse_stream(File.read(path, encoding: "UTF-8")).children
    unless documents.length == 1 && documents.first.root.is_a?(Psych::Nodes::Mapping)
      errors += 1
      warn "CI YAML: #{path.delete_prefix(root + "/")}: expected one YAML mapping"
    end
  rescue Psych::SyntaxError => e
    errors += 1
    warn "CI YAML: #{path.delete_prefix(root + "/")}: invalid syntax at line #{e.line}, column #{e.column}"
  rescue SystemCallError, EncodingError
    errors += 1
    warn "CI YAML: #{path.delete_prefix(root + "/")}: unreadable definition"
  end
end
puts "CI YAML: parsed #{paths.length} definitions; #{errors} invalid"
exit(errors.zero? ? 0 : 1)
