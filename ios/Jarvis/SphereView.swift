import SwiftUI

/// The voice sphere from the web HUD (see DESIGN.md): points of light on a sphere plus three orbital rings.
/// Breathes when idle, ripples with `level` while listening or speaking, swirls while working, greys out offline.
struct SphereView: View {
    enum Mode { case idle, listening, thinking, speaking, offline }

    var mode: Mode
    var level: () -> Double

    @State private var model = SphereModel()

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                model.step(to: timeline.date, mode: mode, target: level())
                model.draw(in: &ctx, size: size)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// Animation state kept outside SwiftUI's diffing; eased toward each mode's look every frame.
final class SphereModel {
    private let points: [(Double, Double, Double, Double)] // x, y, z, seed
    private var t = 0.0, last: Date?
    private var level = 0.0, swirl = 0.0, spin = 0.07, heat = 0.12, gain = 0.85, mono = 0.0

    init(count: Int = 900) {
        let golden = Double.pi * (3 - 5.0.squareRoot())
        points = (0..<count).map { i in // Fibonacci sphere: even spacing
            let y = 1 - Double(i) / Double(count - 1) * 2, r = (1 - y * y).squareRoot(), a = golden * Double(i)
            return (cos(a) * r, y, sin(a) * r, Double.random(in: 0...1))
        }
    }

    func step(to date: Date, mode: SphereView.Mode, target: Double) {
        let dt = min(0.05, last.map { date.timeIntervalSince($0) } ?? 0.016)
        last = date
        t += dt
        let k = 1 - exp(-dt * 3.5)
        let look: (Double, Double, Double, Double) = switch mode {
        case .idle: (0, 0.07, 0.12, 0.85)
        case .listening: (0, 0.04, 0.55, 1.15)
        case .thinking: (1, 0.45, 0.3, 1)
        case .speaking: (0, 0.09, 0.38, 1.05)
        case .offline: (0, 0.02, 0, 0.4)
        }
        swirl += (look.0 - swirl) * k; spin += (look.1 - spin) * k; heat += (look.2 - heat) * k; gain += (look.3 - gain) * k
        mono += ((mode == .offline ? 1 : 0) - mono) * k
        let target = min(1, max(0, target))
        level += (target - level) * (target > level ? 1 - exp(-dt * 30) : 1 - exp(-dt * 6))
    }

    func draw(in ctx: inout GraphicsContext, size: CGSize) {
        let cx = size.width / 2, cy = size.height / 2, scale = min(size.width, size.height) * 0.3
        ctx.blendMode = .plusLighter // light adds up, like a hologram
        // orbital rings: fine, dashed, open arc
        for (radius, tilt, dir, arc) in [(1.34, 1.22, 1.0, 1.0), (1.46, -1.02, -1.0, 1.0), (1.2, 1.5708, 0.6, 0.68)] {
            let steps = 240, ra = t * 0.11 * dir
            for i in 0..<steps {
                if radius > 1.4 && i % 6 > 1 { continue }
                let a = Double(i) / Double(steps) * 2 * .pi * arc + ra
                let x = cos(a) * radius, z0 = sin(a) * radius
                let y = -z0 * sin(tilt), z = z0 * cos(tilt)
                let p = 3.4 / (3.4 - z)
                dot(&ctx, cx + x * p * scale, cy - y * p * scale, 0.9, hot: 0, alpha: (0.22 + 0.38 * front(z)) * gain)
            }
        }
        let rot = t * spin, cr = cos(rot), sr = sin(rot), ct = cos(0.38), st = sin(0.38)
        for (px, py, pz, s) in points {
            let noise = sin(px * 4.1 + py * 3.3 + pz * 5.7 + t * 1.7 + s * 6.283) * 0.5 + 0.5
            let band = sin(py * 9 - t * 7 + s * 2) * 0.5 + 0.5
            let voice = level * (0.08 + 0.24 * noise) * (0.55 + 0.45 * band)
            let r = 1 + 0.018 * sin(t * 1.3 + s * 3) + voice
            let sw = swirl * py * 2.6 * sin(t * 0.9) // swirl: twist by latitude
            let x1 = (px * cos(sw) + pz * sin(sw)) * r, z1 = (-px * sin(sw) + pz * cos(sw)) * r, y1 = py * r
            let x2 = x1 * cr + z1 * sr, z2 = -x1 * sr + z1 * cr
            let y3 = y1 * ct - z2 * st, z3 = y1 * st + z2 * ct
            let p = 3.4 / (3.4 - z3)
            dot(&ctx, cx + x2 * p * scale, cy - y3 * p * scale, (1.05 + level * 0.8) * p,
                hot: min(1, heat + voice * 3), alpha: (0.28 + 0.72 * front(z3)) * gain)
        }
    }

    private func front(_ z: Double) -> Double { min(1, max(0, (z + 1.1) / 2.2)) }

    private func dot(_ ctx: inout GraphicsContext, _ x: Double, _ y: Double, _ r: Double, hot: Double, alpha: Double) {
        let mix = { (a: Double, b: Double, f: Double) in a + (b - a) * f }
        let color = Color(red: mix(mix(0.39, 0.9, hot), 0.45, mono), green: mix(mix(0.89, 0.98, hot), 0.55, mono),
                          blue: mix(1, 0.62, mono), opacity: min(1, max(0, alpha)))
        ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(color))
    }
}
