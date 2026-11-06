import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
afterEach(cleanup);
it("labels demo data and records allowed and rejected decisions after a burst", async () => {
  render(<App />);
  expect(screen.getByText("SIMULATED DATA")).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: /Send burst/ }));
  await waitFor(() =>
    expect(screen.getAllByText("Rejected").length).toBeGreaterThan(1),
  );
  expect(
    screen.getByText("No Java service or Redis is being contacted.", {
      exact: false,
    }),
  ).toBeTruthy();
});
