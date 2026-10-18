// Renders the Google TV banner and the launcher icons: a round window (lukarne = dormer window) onto a dotted
// globe made of the app's own land dots, with one warm light, and the wordmark in Instrument Serif.
// Usage: swift tools/render_brand.swift   (from the repo root; needs app/src/main/assets/land_dots.bin)
import AppKit
import CoreText

let fontURL = URL(fileURLWithPath: "app/src/main/res/font/instrument_serif.ttf")
let serifDesc = (CTFontManagerCreateFontDescriptorsFromURL(fontURL as CFURL) as! [CTFontDescriptor])[0]
func serif(_ size: CGFloat) -> CTFont { CTFontCreateWithFontDescriptor(serifDesc, size, nil) }

func rgb(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}
let ink = rgb(0x05070C), deep = rgb(0x0A1020), ocean = rgb(0x0C1528), rim = rgb(0x6E8BC2)
let fg = rgb(0xF1ECE2), light = rgb(0xF2B35B), hot = rgb(0xFFE2B0)

// Land dots: float32 LE (x, y, z) unit vectors.
let data = try! Data(contentsOf: URL(fileURLWithPath: "app/src/main/assets/land_dots.bin"))
let dots: [(Double, Double, Double)] = data.withUnsafeBytes { raw in
    let f = raw.bindMemory(to: Float32.self)
    return stride(from: 0, to: f.count, by: 3).map { (Double(f[$0]), Double(f[$0 + 1]), Double(f[$0 + 2])) }
}

// Land lookup: dots bucketed in 2° cells; a point is land when a land dot lies within ~0.75°.
var cells: [Int: [(Double, Double, Double)]] = [:]
func cellKey(_ lat: Double, _ lon: Double) -> Int { Int(floor((lat + 90) / 2)) * 1000 + Int(floor((lon + 180) / 2)) }
for d in dots { cells[cellKey(asin(d.2) * 180 / .pi, atan2(d.1, d.0) * 180 / .pi), default: []].append(d) }
func isLand(_ p: (Double, Double, Double)) -> Bool {
    let lat = asin(max(-1, min(1, p.2))) * 180 / .pi, lon = atan2(p.1, p.0) * 180 / .pi
    let cosMax = cos(0.75 * .pi / 180)
    for dl in [-2.0, 0, 2] { for dn in [-2.0, 0, 2] {
        for d in cells[cellKey(lat + dl, lon + dn)] ?? [] where d.0 * p.0 + d.1 * p.1 + d.2 * p.2 > cosMax { return true }
    } }
    return false
}

func render(_ w: Int, _ h: Int, to path: String, draw: (CGContext) -> Void) {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    draw(ctx)
    try! FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    try! NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
    print("wrote \(path)")
}

/// The mark: the window frame, the globe seen through it (centred on the North Atlantic), one light on Montréal.
func mark(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, r: CGFloat) {
    let lat0 = 38.0 * .pi / 180, lon0 = -45.0 * .pi / 180
    let e = (-sin(lon0), cos(lon0), 0.0)
    let n = (-sin(lat0) * cos(lon0), -sin(lat0) * sin(lon0), cos(lat0))
    let u = (cos(lat0) * cos(lon0), cos(lat0) * sin(lon0), sin(lat0))
    let gr = r * 1.18   // the globe is a little larger than the window: we look *through* it

    ctx.saveGState()
    ctx.addEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)); ctx.clip()
    let grad = CGGradient(colorsSpace: nil, colors: [ocean, deep, ink] as CFArray, locations: [0, 0.6, 1])!
    ctx.drawRadialGradient(grad, startCenter: CGPoint(x: cx - r * 0.3, y: cy + r * 0.35), startRadius: 0,
                           endCenter: CGPoint(x: cx, y: cy), endRadius: r * 1.4, options: [.drawsAfterEndLocation])
    // A regular screen-space grid of dots, each kept when it lands on land: the classic dotted map, legible at
    // icon size (the app's own 0.014 rad dot spacing would be ~1 px here).
    let step = max(3.2, r * 0.075)
    let dot = step * 0.58
    var y = cy - r
    while y <= cy + r {
        var x = cx - r
        while x <= cx + r {
            let qx = Double((x - cx) / gr), qy = Double((y - cy) / gr)
            let q2 = qx * qx + qy * qy
            if q2 < 1 {
                let w = sqrt(1 - q2)
                let p = (e.0 * qx + n.0 * qy + u.0 * w, e.1 * qx + n.1 * qy + u.1 * w, e.2 * qx + n.2 * qy + u.2 * w)
                if isLand(p) {
                    ctx.setFillColor(rgb(0xB9C8E2, CGFloat(0.30 + 0.6 * w)))
                    ctx.fillEllipse(in: CGRect(x: x - dot / 2, y: y - dot / 2, width: dot, height: dot))
                }
            }
            x += step
        }
        y += step
    }
    // Montréal, 45.5° N 73.6° W.
    let la = 45.5 * .pi / 180, lo = -73.6 * .pi / 180
    let p = (cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    let lx = cx + gr * CGFloat(p.0 * e.0 + p.1 * e.1)
    let ly = cy + gr * CGFloat(p.0 * n.0 + p.1 * n.1 + p.2 * n.2)
    let glow = CGGradient(colorsSpace: nil, colors: [rgb(0xF2B35B, 0.9), rgb(0xF2B35B, 0)] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(glow, startCenter: CGPoint(x: lx, y: ly), startRadius: 0, endCenter: CGPoint(x: lx, y: ly),
                           endRadius: r * 0.32, options: [])
    ctx.setFillColor(hot)
    ctx.fillEllipse(in: CGRect(x: lx - r * 0.05, y: ly - r * 0.05, width: r * 0.1, height: r * 0.1))
    ctx.restoreGState()

    // The frame: a thin warm ring with a soft outer glow.
    ctx.setShadow(offset: .zero, blur: r * 0.25, color: rgb(0x6E8BC2, 0.5))
    ctx.setStrokeColor(rgb(0xF1ECE2, 0.9)); ctx.setLineWidth(max(1.5, r * 0.045))
    ctx.strokeEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
    ctx.setShadow(offset: .zero, blur: 0, color: nil)
}

func text(_ s: String, _ font: CTFont, _ color: CGColor, x: CGFloat, y: CGFloat, _ ctx: CGContext) {
    let str = NSAttributedString(string: s, attributes: [.init(kCTFontAttributeName as String): font,
                                                         .init(kCTForegroundColorAttributeName as String): color])
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(CTLineCreateWithAttributedString(str), ctx)
}

func width(_ s: String, _ font: CTFont) -> CGFloat {
    CGFloat(CTLineGetTypographicBounds(CTLineCreateWithAttributedString(
        NSAttributedString(string: s, attributes: [.init(kCTFontAttributeName as String): font])), nil, nil, nil))
}

func ground(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
    ctx.setFillColor(ink); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    let g = CGGradient(colorsSpace: nil, colors: [rgb(0x0C1528), ink] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(g, startCenter: CGPoint(x: w * 0.3, y: h * 0.5), startRadius: 0,
                           endCenter: CGPoint(x: w * 0.3, y: h * 0.5), endRadius: w * 0.7, options: [])
}

// Banner: 320×180 dp at xhdpi = 640×360 px. Mark left, wordmark right, optically centred.
render(640, 360, to: "app/src/main/res/drawable-xhdpi/banner.png") { ctx in
    ground(ctx, 640, 360)
    let f = serif(132)
    let word = "lukarne"
    let w = width(word, f)
    let markR: CGFloat = 70
    let gap: CGFloat = 34
    let x0 = (640 - (2 * markR + gap + w)) / 2
    mark(ctx, cx: x0 + markR, cy: 184, r: markR)
    text(word, f, fg, x: x0 + 2 * markR + gap, y: 148, ctx)
}

// Launcher icons (48 dp): the mark alone on ink.
for (dir, px) in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)] {
    render(px, px, to: "app/src/main/res/\(dir)/ic_launcher.png") { ctx in
        let s = CGFloat(px)
        ground(ctx, s, s)
        mark(ctx, cx: s / 2, cy: s / 2, r: s * 0.36)
    }
}
