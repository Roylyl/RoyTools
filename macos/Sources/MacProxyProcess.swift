#if ROYTOOLS_MAC_PROXY
import Darwin
import Foundation

final class MacProxyProcess: @unchecked Sendable {
    private let pid: pid_t
    private let control: FileHandle
    private let output: FileHandle
    private let writes = DispatchQueue(label: "com.roylyl.tools.mac.proxy-pipe")

    init(executable: URL, arguments: [String]) throws {
        let input = Pipe()
        let output = Pipe()
        var actions: posix_spawn_file_actions_t?
        try Self.check(posix_spawn_file_actions_init(&actions))
        defer { posix_spawn_file_actions_destroy(&actions) }
        let inputRead = input.fileHandleForReading.fileDescriptor
        let inputWrite = input.fileHandleForWriting.fileDescriptor
        let outputRead = output.fileHandleForReading.fileDescriptor
        let outputWrite = output.fileHandleForWriting.fileDescriptor
        guard fcntl(inputWrite, F_SETNOSIGPIPE, 1) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        try Self.check(posix_spawn_file_actions_adddup2(&actions, inputRead, STDIN_FILENO))
        try Self.check(posix_spawn_file_actions_adddup2(&actions, outputWrite, STDOUT_FILENO))
        try Self.check(posix_spawn_file_actions_adddup2(&actions, outputWrite, STDERR_FILENO))
        for descriptor in [inputRead, inputWrite, outputRead, outputWrite] where descriptor > STDERR_FILENO {
            try Self.check(posix_spawn_file_actions_addclose(&actions, descriptor))
        }

        var argv = ([executable.path] + arguments).map { strdup($0) }
        defer { argv.forEach { free($0) } }
        guard argv.allSatisfy({ $0 != nil }) else { throw POSIXError(.ENOMEM) }
        argv.append(nil)

        var child: pid_t = 0
        try Self.check(posix_spawn(&child, executable.path, &actions, nil, &argv, environ))
        try? input.fileHandleForReading.close()
        try? output.fileHandleForWriting.close()
        pid = child
        control = input.fileHandleForWriting
        self.output = output.fileHandleForReading
    }

    func stop() { try? control.close() }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            writes.async {
                continuation.resume(with: Result { try self.control.write(contentsOf: data) })
            }
        }
    }

    func lines() -> AsyncStream<String> {
        AsyncStream { continuation in
            // Independent readers keep the core and the settings helper responsive together.
            DispatchQueue.global(qos: .utility).async {
                defer { continuation.finish() }
                let descriptor = self.output.fileDescriptor
                var chunk = [UInt8](repeating: 0, count: 8192)
                var pending = Data()
                while true {
                    let count = chunk.withUnsafeMutableBytes { Darwin.read(descriptor, $0.baseAddress, $0.count) }
                    if count == -1 && errno == EINTR { continue }
                    guard count > 0 else { return }
                    pending.append(contentsOf: chunk.prefix(count))
                    while let newline = pending.firstIndex(of: 0x0a) {
                        continuation.yield(String(decoding: pending[..<newline], as: UTF8.self))
                        pending.removeSubrange(...newline)
                    }
                    if pending.count > 32 << 20 {
                        self.stop()
                        return
                    }
                }
            }
        }
    }

    func waitForExit() {
        var status: Int32 = 0
        while waitpid(pid, &status, 0) == -1 && errno == EINTR {}
    }

    private static func check(_ result: Int32) throws {
        if result != 0 { throw POSIXError(POSIXErrorCode(rawValue: result) ?? .EIO) }
    }
}
#endif
