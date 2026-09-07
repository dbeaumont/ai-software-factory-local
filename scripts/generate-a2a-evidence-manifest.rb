#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "pathname"

root = Pathname.new(File.expand_path("..", __dir__))
evidence_directory = root.join("docs/evidence/a2a")
manifest = evidence_directory.join("MANIFEST.sha256")

abort("A2A evidence directory does not exist: #{evidence_directory}") unless evidence_directory.directory?

candidates = Dir.glob(evidence_directory.join("**/*").to_s, File::FNM_DOTMATCH).sort
symlinks = candidates.select { |path| File.symlink?(path) }
abort("A2A evidence archive must not contain symlinks: #{symlinks.join(', ')}") unless symlinks.empty?

files = candidates
  .select { |path| File.file?(path) }
  .reject { |path| Pathname.new(path) == manifest }

abort("A2A evidence archive is empty") if files.empty?

expected = files.map do |path|
  relative = Pathname.new(path).relative_path_from(root)
  "#{Digest::SHA256.file(path).hexdigest}  #{relative}"
end.join("\n") + "\n"

case ARGV
when []
  manifest.write(expected)
  puts "A2A evidence manifest generated: #{files.length} files."
when ["--check"]
  abort("A2A evidence manifest is missing; generate it first") unless manifest.file?
  abort("A2A evidence manifest is stale; regenerate it") unless manifest.read == expected

  puts "A2A evidence manifest verified: #{files.length} files."
else
  abort("Usage: #{File.basename($PROGRAM_NAME)} [--check]")
end
