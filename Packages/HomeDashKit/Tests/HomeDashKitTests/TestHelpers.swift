import Foundation
@testable import HomeDashKit

/// Builds a Date from Europe/Madrid wall-clock components.
func madrid(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0) -> Date {
    Constants.madridCalendar.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
}
