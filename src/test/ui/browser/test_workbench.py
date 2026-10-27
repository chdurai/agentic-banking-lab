from playwright.sync_api import expect


def open_workspace(workspace):
    page = workspace["page"]
    page.goto(workspace["url"])
    expect(page.locator("#connection-title")).to_have_text("API connected")
    expect(page.locator('[data-start-flow="loan"]')).to_be_visible()
    return page


def test_guide_and_navigation(workspace):
    page = open_workspace(workspace)
    page.get_by_role("button", name="Cases", exact=False).first.click()
    expect(page).to_have_url(workspace["url"] + "/#cases")
    page.get_by_role("button", name="Review queue", exact=False).first.click()
    expect(page.get_by_text("Your review queue is clear")).to_be_visible()
    page.get_by_role("link", name="How to use", exact=True).click()
    expect(page.get_by_role("heading", name="3. Your first loan demo — start here")).to_be_visible()


def test_existing_applicant_loan(workspace):
    page = open_workspace(workspace)
    page.locator('[data-start-flow="loan"]').click()
    dialog = page.locator("#case-dialog")
    expect(dialog).to_be_visible()
    page.get_by_label("Find by name or customer reference").fill("Jane")
    page.get_by_role("button", name="Search", exact=True).click()
    page.locator('[data-applicant="A001"]').click()
    expect(dialog.get_by_role("heading", name="Jane Cooper")).to_be_visible()
    expect(dialog.get_by_text("Acme Corp", exact=False)).to_be_visible()
    page.get_by_role("button", name="Continue to loan details", exact=False).click()
    page.get_by_label("Requested amount", exact=False).fill("15000")
    page.get_by_label("Loan purpose", exact=False).fill("Home repairs")
    page.get_by_label("Term in months", exact=False).fill("60")
    page.get_by_role("button", name="Review application", exact=False).click()
    page.get_by_label("I reviewed this demo applicant", exact=False).check()
    page.get_by_role("button", name="Confirm and submit", exact=False).click()
    expect(dialog).not_to_be_visible()
    expect(page.get_by_role("heading", name="Case workspace")).to_be_visible()
    expect(page.get_by_text("Synthetic browser fixture outcome", exact=True)).to_be_visible()
    assert len(workspace["submitted"]) == 1
    request = workspace["submitted"][0]
    assert request["key"]
    assert request["body"] == {
        "requestedAmount": 15000, "loanPurpose": "Home repairs", "termMonths": 60,
        "profileVersion": 1, "confirmed": True,
    }
    page.reload()
    expect(page.get_by_text("Synthetic browser fixture outcome", exact=True)).to_be_visible()


def test_case_deep_link(workspace):
    page = workspace["page"]
    page.goto(workspace["url"] + "/#case/browser-case-1")
    expect(page.get_by_role("heading", name="Case workspace")).to_be_visible()
    expect(page.get_by_text("Synthetic browser fixture outcome", exact=True)).to_be_visible()
    assert not workspace["submitted"]


def test_api_failure_is_visible(workspace):
    page = workspace["page"]
    page.route("**/api/v1/cases?*",
               lambda route: route.fulfill(status=503, content_type="application/json",
                                          body='{"detail":"Browser test service unavailable"}'))
    page.goto(workspace["url"])
    page.get_by_role("button", name="Cases", exact=False).first.click()
    expect(page.get_by_text("Browser test service unavailable", exact=False).first).to_be_visible()
    assert not workspace["submitted"]


def test_dialog_keyboard_dismissal(workspace):
    page = open_workspace(workspace)
    launch = page.locator('[data-start-flow="loan"]')
    launch.click()
    expect(page.locator("#case-dialog")).to_be_visible()
    page.keyboard.press("Escape")
    expect(page.locator("#case-dialog")).not_to_be_visible()
    expect(launch).to_be_focused()
