library(dplyr)
library(ggplot2)

MAX_LATENCY <- 5

orchestrator <- read.csv("orchestrator.csv", sep = ";")
choreography <- read.csv("choreography.csv", sep = ";")

latency_bar_plot <- function(chain_len) {
  o <- orchestrator %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "O",
      subtype = "Network",
      time = mean(end_to_end_time) / 1000000,
    )

  c1 <- choreography %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "A",
      subtype = "Sidecar",
      time = mean(sidecar) / 1000000,
    )

  c2 <- choreography %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "A",
      subtype = "Network",
      time = (mean(total) - mean(sidecar)) / 1000000,
    )

  data <- bind_rows(o, c1, c2)

  milliseconds <- function(x) {
    paste(x, "ms")
  }

  ggplot(data, aes(x = type, y = time, fill = subtype)) +
    geom_bar(position = "stack", stat = "identity") +
    scale_fill_manual(values = c("#F15350", "#38B84D")) +
    facet_grid(~simulated_latency, labeller = as_labeller(milliseconds)) +
    theme(
      text = element_text(size = 20),
      # strip.placement = "outside",
      strip.background = element_rect(fill = NA, color = "white"),
      # panel.spacing = unit(-.01,"cm")
      # axis.text.x = element_text(angle = 45, vjust = 1, hjust=1)
      plot.title = element_text(hjust = 0.5),
      plot.subtitle = element_text(hjust = 0.5),
    ) +
    labs(
      title = "Network latency",
      subtitle = sprintf("Chain of %d services", chain_len),
      # caption = "Caption",
      # tag = "Tag",
      # alt = "Alt",
      # alt_insight = "Alt insight"
      fill = NULL,
      x = NULL, # "Network latency",
      y = "End-to-end latency",
    ) +
    scale_y_continuous(labels = milliseconds)
}

p1 <- latency_bar_plot(1)
p3 <- latency_bar_plot(3)
p5 <- latency_bar_plot(5)

ggsave("latency_bars_1_sidecar.png", p1, width = 6, height = 4)
ggsave("latency_bars_3_sidecar.png", p3, width = 6, height = 4)
ggsave("latency_bars_5_sidecar.png", p5, width = 6, height = 4)

# print(p1)
# print(p3)
# print(p5)
